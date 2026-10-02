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
 *
 * <p>耗时分布只使用 {@link LatencyBucket} 中写死的固定档位：每次 {@link #record} 让请求
 * 恰好落入一个档位，所有档位计数之和恒等于总请求数。档位数量编译期有界，内存占用
 * O(路由数 × 固定档位数)，不随请求量或 URL 增长。</p>
 */
public final class RouteMetrics {

    private final String routeTemplate;
    private final LongAdder totalCount = new LongAdder();
    private final LongAdder totalDurationNanos = new LongAdder();
    private final AtomicLong maxDurationNanos = new AtomicLong();
    private final Map<ErrorClass, LongAdder> countsByErrorClass = new EnumMap<>(ErrorClass.class);
    private final Map<LatencyBucket, LongAdder> countsByLatencyBucket = new EnumMap<>(LatencyBucket.class);

    public RouteMetrics(String routeTemplate) {
        if (routeTemplate == null || routeTemplate.isBlank()) {
            throw new IllegalArgumentException("routeTemplate must not be blank");
        }
        this.routeTemplate = routeTemplate;
        for (ErrorClass errorClass : ErrorClass.values()) {
            countsByErrorClass.put(errorClass, new LongAdder());
        }
        for (LatencyBucket bucket : LatencyBucket.values()) {
            countsByLatencyBucket.put(bucket, new LongAdder());
        }
    }

    /**
     * 记录一次请求：增加总计数与该错误分类计数、累加耗时、更新最大值，
     * 并把该请求<strong>恰好</strong>计入一个固定耗时档位。
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
        countsByLatencyBucket.get(LatencyBucket.bucketOfNanos(durationNanos)).increment();
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

    /** 指定耗时档位的请求数；与总计数使用同一批 {@link LongAdder}，并发下无丢失无重复。 */
    public long getBucketCount(LatencyBucket bucket) {
        return countsByLatencyBucket.get(bucket).sum();
    }

    /** 所有固定档位计数之和；结构上恒等于 {@link #getCount()}（每请求恰好落一档）。 */
    public long getBucketCountSum() {
        long sum = 0L;
        for (LongAdder adder : countsByLatencyBucket.values()) {
            sum += adder.sum();
        }
        return sum;
    }

    public long getTotalNanos() {
        return totalDurationNanos.sum();
    }

    public long getMaxNanos() {
        return maxDurationNanos.get();
    }
}
