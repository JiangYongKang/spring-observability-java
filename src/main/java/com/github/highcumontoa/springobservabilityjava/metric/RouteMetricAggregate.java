package com.github.highcumontoa.springobservabilityjava.metric;

import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * 单一路由维度的聚合计数。
 *
 * <p>全部字段使用 JUC 高并发原语：{@link LongAdder}（计数/总耗时）在高并发
 * 写入下通过分段累加避免 CAS 争用，计数不丢失；{@link LongAccumulator}
 * 维护最小/最大耗时。请求结束只做一次 {@code record}，天然不会重复。
 */
public final class RouteMetricAggregate {

    private final RouteMetricKey key;
    private final LongAdder count = new LongAdder();
    private final LongAdder totalNanos = new LongAdder();
    private final LongAccumulator minNanos =
            new LongAccumulator(Math::min, Long.MAX_VALUE);
    private final LongAccumulator maxNanos =
            new LongAccumulator(Long::max, 0L);

    public RouteMetricAggregate(RouteMetricKey key) {
        this.key = key;
    }

    public RouteMetricKey getKey() {
        return key;
    }

    public void record(long durationNanos) {
        count.increment();
        totalNanos.add(durationNanos);
        minNanos.accumulate(durationNanos);
        maxNanos.accumulate(durationNanos);
    }

    public long getCount() {
        return count.sum();
    }

    public long getTotalNanos() {
        return totalNanos.sum();
    }

    public long getMinNanos() {
        long v = minNanos.get();
        return v == Long.MAX_VALUE ? 0L : v;
    }

    public long getMaxNanos() {
        return maxNanos.get();
    }

    public double getAverageNanos() {
        long c = getCount();
        return c == 0 ? 0d : (double) getTotalNanos() / c;
    }
}
