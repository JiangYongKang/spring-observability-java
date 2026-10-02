package com.github.highcumontoa.springobservabilityjava.observability.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 路由指标注册表（进程内、无外部依赖）。
 *
 * <p>基数约束：只接受已经匹配到控制器路由模板的 key（由拦截器保证），key 集合上界为应用
 * 注册的路由数量；无法归属路由的请求统一计入固定桶 {@link #UNMAPPED_TEMPLATE}，
 * 绝不会按原始 URI/路径参数新建维度，避免基数爆炸污染指标。</p>
 *
 * <p><strong>统计重置（批次隔离）</strong>：内部以 {@link Generation}（代）保存计数器，
 * 每个 generation 是一套独立计数器。{@link #reset()} 原子地把"当前代"切换为一套全新的
 * 空计数器，并冻结、返回上一代的快照。</p>
 *
 * <p><strong>并发边界</strong>：记录走共享读锁（彼此完全并发），重置走独占写锁。
 * 这保证一次记录<strong>整体</strong>地发生在重置之前或之后——不允许"重置前读旧代、
 * 重置后写旧代"这种跨越边界的半截记录：</p>
 * <ul>
 *   <li>在重置获取写锁前完成的记录 → 全部留在旧批次，并出现在冻结快照里；</li>
 *   <li>重置期间或之后完成的记录 → 阻塞到切换完成，落入新批次，从零开始累计；</li>
 *   <li>每次记录只递增一次，因此不重复计数、不丢失计数，
 *       重置前后的请求不可能出现在同一批数据里；重置本身可被并发安全地反复调用。</li>
 * </ul>
 */
public final class MetricsRegistry {

    /** 无法归属到路由模板的请求（如未匹配处理器的请求）使用的固定维度值。 */
    public static final String UNMAPPED_TEMPLATE = "UNMAPPED";

    private final AtomicReference<Generation> current;
    private final ReadWriteLock resetLock = new ReentrantReadWriteLock();

    public MetricsRegistry() {
        this.current = new AtomicReference<>(new Generation(0L, System.currentTimeMillis()));
    }

    /**
     * 按路由模板记录一次请求。整个记录在共享读锁下完成，与重置的写锁互斥：
     * 因此本次记录要么完整计入重置前批次，要么完整计入重置后批次，恰好属于一个批次。
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
     * 原子重置统计起点：切换到全新的空批次（序号 +1），并返回被冻结的上一批次快照。
     *
     * <p>持有独占写锁期间没有任何记录在进行，因此：返回时旧批次的所有计数就是
     * 最终值（快照逐一拷贝原始数值），而之后完成的记录必然落入新批次。</p>
     */
    public RegistrySnapshot reset() {
        resetLock.writeLock().lock();
        try {
            Generation previous = current.get();
            long resetAt = System.currentTimeMillis();

            List<RouteSnapshot> routes = new ArrayList<>();
            for (RouteMetrics metrics : previous.routes()) {
                routes.add(RouteSnapshot.from(previous.id(), metrics));
            }
            RegistrySnapshot frozen = new RegistrySnapshot(
                    previous.id(), previous.startedAtEpochMillis(), resetAt, routes);

            // 切换在快照之后：此刻不存在任何针对旧代的进行中记录（写锁独占）
            current.set(new Generation(previous.id() + 1, resetAt));
            return frozen;
        } finally {
            resetLock.writeLock().unlock();
        }
    }
}
