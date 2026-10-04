package com.github.highcumontoa.springobservabilityjava.observability.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 一个统计批次（generation）：拥有自己独立的一套路由计数器，并记录本批次内<strong>在途</strong>
 * （已进入、未结束）的请求数。
 *
 * <p>重置 = 在注册表层面整体替换 generation。请求<strong>进入时</strong>在当前代上占一个在途名额
 * （{@link #enter()}）并领取指向该代的 {@link RequestBatch}；<strong>结束时</strong>记回同一代
 * （{@link RequestBatch#finish}）再释放名额（{@link #exit()}）。因此：</p>
 * <ul>
 *   <li>重置前进入的请求，哪怕重置之后很久才结束，也只会写入旧代，绝不混入新批次；</li>
 *   <li>重置在冻结旧代前先 {@link #awaitIdle} 排空它的在途请求，所以冻结快照必然包含
 *       所有"重置前进入"的请求，没有任何一笔被丢到两批之外；</li>
 *   <li>每次请求只在它进入时的唯一一代上 finish 一次，不重复、不丢失。</li>
 * </ul>
 */
final class Generation {

    private final long id;
    private final long startedAtEpochMillis;
    private final ConcurrentMap<String, RouteMetrics> routes = new ConcurrentHashMap<>();
    private final RouteMetrics unmapped = new RouteMetrics(MetricsRegistry.UNMAPPED_TEMPLATE);

    /** 在途请求数与排空等待：enter/exit/awaitIdle 之间用同一把锁保护。 */
    private final ReentrantLock inflightLock = new ReentrantLock();
    private final Condition idle = inflightLock.newCondition();
    private int inFlight;

    Generation(long id, long startedAtEpochMillis) {
        this.id = id;
        this.startedAtEpochMillis = startedAtEpochMillis;
    }

    long id() {
        return id;
    }

    long startedAtEpochMillis() {
        return startedAtEpochMillis;
    }

    /** 请求进入：占用一个在途名额。一旦在旧代上占用，重置就必须等它结束才能冻结该代。 */
    void enter() {
        inflightLock.lock();
        try {
            inFlight++;
        } finally {
            inflightLock.unlock();
        }
    }

    /** 请求结束：释放名额，并在排空时唤醒等待中的重置。 */
    void exit() {
        inflightLock.lock();
        try {
            inFlight--;
            if (inFlight == 0) {
                idle.signalAll();
            }
        } finally {
            inflightLock.unlock();
        }
    }

    /**
     * 阻塞直到本批次没有任何在途请求（重置冻结前调用）。
     * 调用方已持有注册表重置写锁，且 {@code finish} 不获取该写锁，因此不会死锁：
     * 在途请求写计数 + {@link #exit()} 可以正常进行并最终把在途数降到 0。
     *
     * @return true 表示已排空；false 表示等待超时（保守起见仍继续冻结）
     */
    boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
        inflightLock.lock();
        try {
            long remainingNanos = unit.toNanos(timeout);
            while (inFlight > 0 && remainingNanos > 0L) {
                remainingNanos = idle.awaitNanos(remainingNanos);
            }
            return inFlight == 0;
        } finally {
            inflightLock.unlock();
        }
    }

    /** 按路由模板取计数器；{@code null}/{@code UNMAPPED} 进固定桶，其余原子建桶。 */
    RouteMetrics metricsFor(String routeTemplate) {
        if (routeTemplate == null || MetricsRegistry.UNMAPPED_TEMPLATE.equals(routeTemplate)) {
            return unmapped;
        }
        return routes.computeIfAbsent(routeTemplate, RouteMetrics::new);
    }

    /** 本批次全部路由计数器（含固定 UNMAPPED 桶）。 */
    List<RouteMetrics> routes() {
        List<RouteMetrics> all = new ArrayList<>(routes.size() + 1);
        all.addAll(routes.values());
        all.add(unmapped);
        return all;
    }
}
