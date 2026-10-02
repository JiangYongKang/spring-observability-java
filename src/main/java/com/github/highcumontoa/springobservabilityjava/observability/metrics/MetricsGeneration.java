package com.github.highcumontoa.springobservabilityjava.observability.metrics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 指标的一个"统计批次"（代次）：从某次（重新）开始统计的时刻起累计的全部路由指标。
 *
 * <p>每个请求在进入观测时被钉住<strong>当时</strong>的代次（见 {@code MetricsRegistry}），
 * 其指标只会写入该代次；{@code reset} 之后到达的请求写入新代次。于是重置前后的请求
 * 结构性地不可能混到同一批里——不是靠事后从合计数里扣除，而是写入目标从一开始就不同。</p>
 *
 * <p>路由 key 仍只接受路由模板或固定桶 {@link MetricsRegistry#UNMAPPED_TEMPLATE}，
 * 维度基数有界，不随路径参数/URL 增长。</p>
 */
public final class MetricsGeneration {

    private final long generation;
    private final Instant startedAt;
    private final ConcurrentMap<String, RouteMetrics> routes = new ConcurrentHashMap<>();
    private final RouteMetrics unmapped = new RouteMetrics(MetricsRegistry.UNMAPPED_TEMPLATE);
    /** 钉住本代次、尚未完成观测的请求数；归零表示重置前的老请求已全部结清。 */
    private final AtomicInteger inFlight = new AtomicInteger(0);

    MetricsGeneration(long generation, Instant startedAt) {
        this.generation = generation;
        this.startedAt = startedAt;
    }

    /** 一个请求进入观测：钉住本代次，在途计数 +1。必须与一次 {@link #leave()} 配对。 */
    void enter() {
        inFlight.incrementAndGet();
    }

    /**
     * 该请求观测结束：在途计数 -1 并唤醒等待结零的 reset 线程。
     * 每次离开都通知（reset 等待条件可能是"≤1"以排除重置请求自身），避免漏唤醒。
     */
    void leave() {
        inFlight.decrementAndGet();
        synchronized (inFlight) {
            inFlight.notifyAll();
        }
    }

    int inFlightCount() {
        return inFlight.get();
    }

    /**
     * 等待本代次在途数降到不超过 {@code excludedInFlight}。重置请求自身在调用 reset 时
     * 必然仍处于在途状态（要等本方法返回后它才能结束），因此把它排除，避免自己等自己。
     */
    void awaitUntilInFlightAtMost(int excludedInFlight, long waitMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + waitMillis;
        synchronized (inFlight) {
            while (inFlight.get() > excludedInFlight) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    // 有界等待只是防御性兜底（正常请求都是毫秒级）；超时也不丢计数，
                    // 老代次继续独立累计，新代次照常服务。
                    return;
                }
                inFlight.wait(remaining);
            }
        }
    }

    RouteMetrics metricsFor(String routeTemplate) {
        if (routeTemplate == null || MetricsRegistry.UNMAPPED_TEMPLATE.equals(routeTemplate)) {
            return unmapped;
        }
        return routes.computeIfAbsent(routeTemplate, RouteMetrics::new);
    }

    void record(String routeTemplate, long durationNanos, ErrorClass errorClass) {
        metricsFor(routeTemplate).record(durationNanos, errorClass);
    }

    /** 路由模板排序后导出，附带固定 UNMAPPED 桶；同一次快照内顺序确定。 */
    List<RouteMetrics> snapshot() {
        List<String> keys = new ArrayList<>(routes.keySet());
        Collections.sort(keys);
        List<RouteMetrics> all = new ArrayList<>(keys.size() + 1);
        for (String key : keys) {
            all.add(routes.get(key));
        }
        all.add(unmapped);
        return all;
    }

    public long getGeneration() {
        return generation;
    }

    public Instant getStartedAt() {
        return startedAt;
    }
}
