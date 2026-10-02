package com.github.highcumontoa.springobservabilityjava.observability.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 一个统计批次（generation）：拥有自己独立的一套路由计数器。
 *
 * <p>重置 = 在注册表层面整体替换 generation。旧 generation 不再被新写入触及，
 * 因此重置前后记录的请求天然落在两套不同计数器里，不可能混入同一批；
 * 每次 {@code record} 只在它解析到的唯一一个 generation 上递增一次，不重复、不丢失。</p>
 */
final class Generation {

    private final long id;
    private final long startedAtEpochMillis;
    private final ConcurrentMap<String, RouteMetrics> routes = new ConcurrentHashMap<>();
    private final RouteMetrics unmapped = new RouteMetrics(MetricsRegistry.UNMAPPED_TEMPLATE);

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
