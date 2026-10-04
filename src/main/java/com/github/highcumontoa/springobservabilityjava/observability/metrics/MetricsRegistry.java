package com.github.highcumontoa.springobservabilityjava.observability.metrics;

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
 * <p><strong>批次归属按"进入时刻"判定</strong>：请求进入时调用 {@link #currentBatch()}
 * 固定归属的代，完成时通过 {@link #record(Generation, String, long, ErrorClass)} 计入该代。
 * 因此重置之前已进入的请求，无论在重置后多久才结束，都只计入旧（已冻结）批次；
 * 新批次只累计重置之后才进入的请求，边界干净、不混批。</p>
 *
 * <p><strong>并发边界</strong>：归属判定（{@link #currentBatch()}）与即时记录
 * （{@link #record(String, long, ErrorClass)}）走共享读锁（彼此完全并发），重置走独占写锁。
 * 这保证归属判定<strong>整体</strong>地发生在重置之前或之后——不允许"重置前读旧代、
 * 重置后写旧代"这种跨越边界的半截判定：</p>
 * <ul>
 *   <li>在重置获取写锁前完成归属判定的请求 → 计入旧批次，被冻结快照吸收；</li>
 *   <li>重置期间或之后判定归属的请求 → 阻塞到切换完成，落入新批次，从零开始累计；</li>
 *   <li>每次记录只在进入时判定的那一个代上递增一次，因此不重复计数、不丢失计数，
 *       每笔请求恰好属于一个批次；重置本身可被并发安全地反复调用。</li>
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
     * 按路由模板记录一次请求（即时归属：计入调用这一刻的当前批次）。
     * 整个归属判定与记录在共享读锁下完成，与重置的写锁互斥：
     * 因此本次记录要么完整计入重置前批次，要么完整计入重置后批次，恰好属于一个批次。
     * {@code null} 或 {@link #UNMAPPED_TEMPLATE} 进入固定桶。
     *
     * <p>Web 请求应改用"进入时定归属"的组合：进入时 {@link #currentBatch()}，
     * 完成时 {@link #record(Generation, String, long, ErrorClass)}。</p>
     */
    public void record(String routeTemplate, long durationNanos, ErrorClass errorClass) {
        resetLock.readLock().lock();
        try {
            current.get().metricsFor(routeTemplate).record(durationNanos, errorClass);
        } finally {
            resetLock.readLock().unlock();
        }
    }

    /**
     * 返回"此刻进入的请求"应当归属的批次句柄。归属判定在共享读锁下完成，
     * 与重置的写锁互斥：返回的要么是重置前的旧代，要么是切换完成后的新代，绝不跨边界。
     */
    public Generation currentBatch() {
        resetLock.readLock().lock();
        try {
            return current.get();
        } finally {
            resetLock.readLock().unlock();
        }
    }

    /**
     * 把一次完成的请求计入它在进入时固定下来的批次 {@code batch}。
     * 即使该批次已被重置冻结（请求在重置前进入、重置后才结束），也只计入这个旧批次，
     * 不会落入当前新批次。批次对象本身的计数器线程安全，此处无需再加锁。
     */
    public void record(Generation batch, String routeTemplate, long durationNanos, ErrorClass errorClass) {
        if (batch == null) {
            throw new IllegalArgumentException("batch must not be null");
        }
        batch.metricsFor(routeTemplate).record(durationNanos, errorClass);
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
     * <p>持有独占写锁期间没有任何归属判定在进行，因此切换瞬间不存在"半新半旧"的请求：
     * 之前进入的请求全部归属旧批次，之后进入的全部归属新批次。旧批次对"新进入"关闭，
     * 但重置前已进入、重置后才结束的在途请求完成时仍计入旧批次——
     * 冻结快照是对旧批次计数器的实时视图，会随之反映这些迟到的完成。</p>
     */
    public RegistrySnapshot reset() {
        resetLock.writeLock().lock();
        try {
            Generation previous = current.get();
            long resetAt = System.currentTimeMillis();

            // 冻结视图包装旧批次（实时读取，迟到的在途完成会反映到后续访问）；
            // 切换在包装之后：此刻不存在任何针对旧代的进行中归属判定（写锁独占）
            RegistrySnapshot frozen = RegistrySnapshot.from(previous, resetAt);
            current.set(new Generation(previous.id() + 1, resetAt));
            return frozen;
        } finally {
            resetLock.writeLock().unlock();
        }
    }
}
