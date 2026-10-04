package com.github.highcumontoa.springobservabilityjava.observability.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 路由指标注册表（进程内、无外部依赖）。
 *
 * <p>基数约束：只接受已经匹配到控制器路由模板的 key（由拦截器保证），key 集合上界为应用
 * 注册的路由数量；无法归属路由的请求统一计入固定桶 {@link #UNMAPPED_TEMPLATE}，
 * 绝不会按原始 URI/路径参数新建维度，避免基数爆炸污染指标。</p>
 *
 * <p><strong>归属边界 = 请求进入时刻（而非完成时刻）</strong>：每个请求在进入时调用
 * {@link #openBatch()} 原子地领取"当前代"票据（{@link RequestBatch}），并在该代上占一个在途名额；
 * 请求结束时凭票据记回<strong>进入时的同一代</strong>再释放名额。因此重置前进入、重置后才结束的
 * 在途请求只会留在被冻结的旧批次，新一批只累计"重置之后才进入"的请求。</p>
 *
 * <p><strong>统计重置（批次隔离）</strong>：{@link #reset()} 原子地把"当前代"切换为全新空计数器，
 * 并冻结、返回上一代快照。冻结前先<strong>排空</strong>旧代的在途请求（持写锁等待其完成），
 * 故被冻结的旧批次必定包含所有"重置前进入"的请求，没有一笔落到两批之外。</p>
 *
 * <p><strong>并发边界</strong>：进入领取走共享读锁（彼此完全并发），重置走独占写锁。
 * 这保证一个请求<strong>整体</strong>地归属到重置前或重置后的某一代：</p>
 * <ul>
 *   <li>在重置获取写锁前进入的请求 → 占住旧代名额，重置必须等它结束，计数留在旧批次并进入冻结快照；</li>
 *   <li>重置之后进入的请求 → 领取全新空批次，从零开始累计；</li>
 *   <li>每个请求只在唯一一代上记录一次，因此不重复计数、不丢失计数，也不可能同时落到两批。</li>
 * </ul>
 */
public final class MetricsRegistry {

    private static final Logger log = LoggerFactory.getLogger(MetricsRegistry.class);

    /** 无法归属到路由模板的请求（如未匹配处理器的请求）使用的固定维度值。 */
    public static final String UNMAPPED_TEMPLATE = "UNMAPPED";

    /** 重置时等待旧批次在途请求排空的上限；正常请求都远早于此完成，仅用于避免永久阻塞。 */
    private static final long DRAIN_TIMEOUT_SECONDS = 30L;

    private final AtomicReference<Generation> current;
    private final ReadWriteLock resetLock = new ReentrantReadWriteLock();

    public MetricsRegistry() {
        this.current = new AtomicReference<>(new Generation(0L, System.currentTimeMillis()));
    }

    /**
     * 请求进入时领取归属批次：原子地取到当前代、占一个在途名额。
     * 整个领取在共享读锁下完成，与重置的写锁互斥，因此进入动作要么发生在重置前（拿到旧代），
     * 要么发生在重置后（拿到新代），不存在跨边界的半截归属。
     */
    RequestBatch openBatch() {
        resetLock.readLock().lock();
        try {
            return RequestBatch.open(current.get());
        } finally {
            resetLock.readLock().unlock();
        }
    }

    /**
     * 直接按路由模板记录一次请求（不经过"进入/结束"生命周期的便捷入口，语义等价于进入即结束）。
     * {@code null} 或 {@link #UNMAPPED_TEMPLATE} 进入固定桶。
     */
    public void record(String routeTemplate, long durationNanos, ErrorClass errorClass) {
        resetLock.readLock().lock();
        try {
            current.get().metricsFor(routeTemplate).record(durationNanos, errorClass);
        } finally {
            resetLock.readLock().unlock();
        }
    }

    /** 返回当前批次内指定路由模板（含固定 UNMAPPED 桶）的指标；不存在则原子创建。 */
    public RouteMetrics metricsFor(String routeTemplate) {
        resetLock.readLock().lock();
        try {
            return current.get().metricsFor(routeTemplate);
        } finally {
            resetLock.readLock().unlock();
        }
    }

    /** 导出当前批次的实时指标（含 UNMAPPED 桶）；读取期间不会与重置交错，得到自洽视图。 */
    public List<RouteMetrics> snapshot() {
        resetLock.readLock().lock();
        try {
            return current.get().routes();
        } finally {
            resetLock.readLock().unlock();
        }
    }

    /** 当前批次序号（首个批次为 0，每重置一次加 1）。 */
    public long currentGenerationId() {
        resetLock.readLock().lock();
        try {
            return current.get().id();
        } finally {
            resetLock.readLock().unlock();
        }
    }

    /**
     * 原子重置统计起点：切换到全新空批次（序号 +1），并返回被冻结的上一批次快照。
     *
     * <p>持独占写锁后先等待旧代的在途请求全部结束（{@code finish} 不获取写锁，故不会死锁）：
     * 最后一个在途请求的计数落定并释放名额后，才对旧代做快照，因此冻结数据包含且仅包含
     * "重置之前已经进入"的请求；此后没有任何请求再能写入旧代（进入只拿得到新代）。</p>
     */
    public RegistrySnapshot reset() {
        resetLock.writeLock().lock();
        try {
            Generation previous = current.get();
            long resetAt = System.currentTimeMillis();

            boolean drained = previous.awaitIdle(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!drained) {
                // 仅当存在超过排空上限仍未结束（疑似挂死）的请求时才会走到这里
                log.warn("reset proceeded after drain timeout ({}s); generation={} may still have in-flight requests",
                        DRAIN_TIMEOUT_SECONDS, previous.id());
            }

            List<RouteSnapshot> routes = new ArrayList<>();
            for (RouteMetrics metrics : previous.routes()) {
                routes.add(RouteSnapshot.from(previous.id(), metrics));
            }
            RegistrySnapshot frozen = new RegistrySnapshot(
                    previous.id(), previous.startedAtEpochMillis(), resetAt, routes);

            // 切换在快照之后：旧代已无在途请求，进入侧只会拿到新代
            current.set(new Generation(previous.id() + 1, resetAt));
            return frozen;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while draining in-flight requests before reset", e);
        } finally {
            resetLock.writeLock().unlock();
        }
    }
}
