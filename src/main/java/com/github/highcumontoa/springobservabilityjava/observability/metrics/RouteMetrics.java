package com.github.highcumontoa.springobservabilityjava.observability.metrics;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 单条路由模板（如 {@code GET /api/items/{itemId}}）的聚合指标。
 *
 * <p>所有计数均使用 {@link LongAdder}（高并发分段累加，无丢失无重复）；
 * 最大耗时用 {@link AtomicLong} CAS 更新。每个路由模板对应一个不可变 key 的实例，
 * 维度基数 = 应用注册的路由模板数量（有明确上界），不会随路径参数增长。</p>
 */
public final class RouteMetrics {

    private final String routeTemplate;
    private final LongAdder totalCount = new LongAdder();
    private final LongAdder totalDurationNanos = new LongAdder();
    private final AtomicLong maxDurationNanos = new AtomicLong();
    private final Map<ErrorClass, LongAdder> countsByErrorClass = new EnumMap<>(ErrorClass.class);
    private final Map<DurationBucket, LongAdder> countsByDurationBucket =
            new EnumMap<>(DurationBucket.class);

    public RouteMetrics(String routeTemplate) {
        if (routeTemplate == null || routeTemplate.isBlank()) {
            throw new IllegalArgumentException("routeTemplate must not be blank");
        }
        this.routeTemplate = routeTemplate;
        for (ErrorClass errorClass : ErrorClass.values()) {
            countsByErrorClass.put(errorClass, new LongAdder());
        }
        // 固定档位数组，内存占用与路由数 × 档位数成正比（均为编译期常量），与请求量无关
        for (DurationBucket bucket : DurationBucket.values()) {
            countsByDurationBucket.put(bucket, new LongAdder());
        }
    }

    /**
     * 记录一次请求：增加总计数与该错误分类计数，累加耗时、更新最大值，
     * 并把这次请求<strong>恰好</strong>计入一个耗时档位。
     */
    public void record(long durationNanos, ErrorClass errorClass) {
        if (durationNanos < 0) {
            throw new IllegalArgumentException("durationNanos must be non-negative");
        }
        if (errorClass == null) {
            throw new IllegalArgumentException("errorClass must not be null");
        }
        totalCount.increment();
        totalDurationNanos.add(durationNanos);
        countsByErrorClass.get(errorClass).increment();
        countsByDurationBucket.get(DurationBucket.forNanos(durationNanos)).increment();
        maxDurationNanos.accumulateAndGet(durationNanos, Math::max);
    }

    public String getRouteTemplate() {
        return routeTemplate;
    }

    public long getCount() {
        return totalCount.sum();
    }

    public long getCount(ErrorClass errorClass) {
        return countsByErrorClass.get(errorClass).sum();
    }

    public long getTotalNanos() {
        return totalDurationNanos.sum();
    }

    public long getMaxNanos() {
        return maxDurationNanos.get();
    }

    /** 指定耗时档位的计数。 */
    public long getCount(DurationBucket bucket) {
        return countsByDurationBucket.get(bucket).sum();
    }

    /** 导出固定顺序（按上界从小到大）的耗时分布视图。 */
    public Map<DurationBucket, Long> durationHistogram() {
        Map<DurationBucket, Long> histogram = new EnumMap<>(DurationBucket.class);
        for (DurationBucket bucket : DurationBucket.values()) {
            histogram.put(bucket, countsByDurationBucket.get(bucket).sum());
        }
        return histogram;
    }
}
