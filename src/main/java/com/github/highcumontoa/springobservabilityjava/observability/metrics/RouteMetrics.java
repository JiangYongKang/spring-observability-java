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

    public RouteMetrics(String routeTemplate) {
        if (routeTemplate == null || routeTemplate.isBlank()) {
            throw new IllegalArgumentException("routeTemplate must not be blank");
        }
        this.routeTemplate = routeTemplate;
        for (ErrorClass errorClass : ErrorClass.values()) {
            countsByErrorClass.put(errorClass, new LongAdder());
        }
    }

    /** 记录一次请求：增加总计数与该错误分类计数，累加耗时并更新最大值。 */
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
}
