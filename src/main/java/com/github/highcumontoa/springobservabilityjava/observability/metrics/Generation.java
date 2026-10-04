package com.github.highcumontoa.springobservabilityjava.observability.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 一个统计批次（generation）：拥有自己独立的一套路由计数器。
 *
 * <p>重置 = 在注册表层面整体替换当前 generation。请求在<strong>进入时</strong>通过
 * {@link MetricsRegistry#currentBatch()} 固定归属的 generation，完成时计入该 generation——
 * 因此重置之前进入的请求，无论多久之后才结束，都只留在旧批次里，绝不会落入新批次；
 * 旧批次对"新进入的请求"关闭，但仍吸收重置前已进入、重置后才完成的在途请求。</p>
 *
 * <p>对本包之外而言，本类是一个<strong>不透明句柄</strong>：只能被获取并回传给
 * {@link MetricsRegistry#record(Generation, String, long, ErrorClass)}，
 * 所有读写方法均为包级可见。</p>
 */
public final class Generation {

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
