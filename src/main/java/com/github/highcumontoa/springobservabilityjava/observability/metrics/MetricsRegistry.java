package com.github.highcumontoa.springobservabilityjava.observability.metrics;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 路由指标注册表（进程内、无外部依赖），并支持把统计起点重置到一个明确时刻。
 *
 * <p>基数约束：只接受已经匹配到控制器路由模板的 key（由观测层保证），key 集合上界为应用
 * 注册的路由数量；无法归属路由的请求统一计入固定桶 {@link #UNMAPPED_TEMPLATE}，
 * 绝不会按原始 URI/路径参数新建维度，避免基数爆炸污染指标。</p>
 *
 * <h2>重置语义与并发边界</h2>
 * <p>内部维护一个只增不改的代次列表 {@link #generations}（{@link CopyOnWriteArrayList}）：</p>
 * <ul>
 *   <li>每个请求在进入观测时 {@link #acquire()}：在<strong>注册表锁</strong>内钉住当前最新
 *       代次，并把该代次在途计数 +1。钉取与 reset 在同一把锁上全序，
 *       因此"进入老代次"与"切到新代次"不会有模糊窗口；</li>
 *   <li>记录 {@link #record} 只写入被钉住的那个代次；重置<strong>之前</strong>进入的请求
 *       永远写老代次，重置<strong>之后</strong>进入的请求写新代次，两批结构性不混；</li>
 *   <li>{@link #reset()} 在锁内追加一个从零开始的新代次，然后逐一等待所有老代次的在途
 *       请求结清。等待集合在持锁时快照，此后老代次的在途计数不可能再增长（新请求只会
 *       进入新代次）。返回后：新代次快照严格只含重置后进入的请求；老代次冻结保留，
 *       不丢任何计数；</li>
 *   <li>代次对象只增不删，任何被钉住的代次都不会失联（迟到记录不可能成为孤儿）；
 *       代次数量 = 重置次数 + 1，由运维动作决定、有界，不随请求量或 URL 增长。</li>
 * </ul>
 */
public final class MetricsRegistry {

    /** 无法归属到路由模板的请求（如未匹配处理器的请求）使用的固定维度值。 */
    public static final String UNMAPPED_TEMPLATE = "UNMAPPED";

    /** 等待重置前在途请求结清的上限：仅为防御性有界等待，不影响计数正确性。 */
    private static final long QUIESCE_WAIT_MILLIS = 30_000L;

    /** 只增不改的代次列表，下标即代次序号。 */
    private final CopyOnWriteArrayList<MetricsGeneration> generations = new CopyOnWriteArrayList<>();
    /** reset 发布与请求钉取共用的监视器：保证"进入老代次"与"切到新代次"全序。 */
    private final Object lifecycleLock = new Object();
    /** 当前线程在过滤器里钉住的代次：reset 请求自身仍在途，等待结零时需把它排除。 */
    private final ThreadLocal<MetricsGeneration> currentPinned = new ThreadLocal<>();

    public MetricsRegistry() {
        generations.add(new MetricsGeneration(0L, Instant.now()));
    }

    /**
     * 请求进入观测：钉住当前最新代次并登记在途。返回值必须在观测结束时传给
     * {@link #release(MetricsGeneration)}。
     */
    public MetricsGeneration acquire() {
        synchronized (lifecycleLock) {
            MetricsGeneration generation = generations.get(generations.size() - 1);
            generation.enter();
            currentPinned.set(generation);
            return generation;
        }
    }

    /** 请求观测结束：解除在途登记（代次在途下降会唤醒等待该代次结零的 reset）。 */
    public void release(MetricsGeneration generation) {
        generation.leave();
        currentPinned.remove();
    }

    /** 按路由模板记录一次请求到指定代次；{@code null} 或 UNMAPPED 进入固定桶。 */
    public void record(MetricsGeneration generation, String routeTemplate,
                       long durationNanos, ErrorClass errorClass) {
        generation.record(routeTemplate, durationNanos, errorClass);
    }

    /**
     * 兼容直接写入的便捷记录：钉住当前代次、立即记录并归还（如单元测试）。
     */
    public void record(String routeTemplate, long durationNanos, ErrorClass errorClass) {
        MetricsGeneration generation = acquire();
        try {
            record(generation, routeTemplate, durationNanos, errorClass);
        } finally {
            release(generation);
        }
    }

    /** 返回当前代次中指定路由模板（含固定 UNMAPPED 桶）已有的指标；不存在则原子创建。 */
    public RouteMetrics metricsFor(String routeTemplate) {
        return currentGeneration().metricsFor(routeTemplate);
    }

    /** 导出当前代次的一致性快照（含 UNMAPPED 桶），供本地验证端点/测试读取。 */
    public List<RouteMetrics> snapshot() {
        return currentGeneration().snapshot();
    }

    private MetricsGeneration currentGeneration() {
        return generations.get(generations.size() - 1);
    }

    /** 当前代次序号：首次启动为 0，每次成功重置 +1。 */
    public long currentGenerationNumber() {
        return currentGeneration().getGeneration();
    }

    /** 当前代次的统计起点。 */
    public Instant currentStartedAt() {
        return currentGeneration().getStartedAt();
    }

    /**
     * 把统计起点重置为调用时刻：追加一个从零开始的新代次，并等待重置发布前进入的在途
     * 请求（即除新代次外的所有代次）全部结清。返回后 {@link #snapshot()} 只含重置之后
     * 进入的请求；并发在途请求继续写各自被钉住的老代次，不丢、不重、不串批。
     *
     * @return 新代次的描述
     */
    public ResetResult reset() {
        synchronized (lifecycleLock) {
            long nextNumber = currentGeneration().getGeneration() + 1L;
            MetricsGeneration next = new MetricsGeneration(nextNumber, Instant.now());
            // 等待集合 = 新代次之前的全部代次。此后这些代次的在途计数不可能再增长：
            // acquire 与本方法同锁串行，新请求只会进入即将发布的新代次。
            List<MetricsGeneration> previous =
                    List.copyOf(generations.subList(0, generations.size()));
            generations.add(next);
            // 发起 reset 的请求自身此刻必然仍在途（要等本方法返回后才结束）。
            // 等待它所在的老代次结零时排除自己，避免"自己等自己"的死锁。
            MetricsGeneration selfPinned = currentPinned.get();
            for (MetricsGeneration generation : previous) {
                int excluded = (generation == selfPinned) ? 1 : 0;
                try {
                    generation.awaitUntilInFlightAtMost(excluded, QUIESCE_WAIT_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return new ResetResult(next.getGeneration(), next.getStartedAt());
        }
    }

    /**
     * 跨所有代次汇总指定路由的总计数。仅用于并发重置测试做全局守恒校验。
     */
    public long totalCountAcrossLiveGenerations(String routeTemplate) {
        long total = 0L;
        for (MetricsGeneration generation : generations) {
            total += generation.metricsFor(routeTemplate).getCount();
        }
        return total;
    }

    /** 重置结果：新代次序号与统计起点。 */
    public record ResetResult(long generation, Instant startedAt) {
    }
}
