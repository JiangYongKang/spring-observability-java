package com.github.highcumontoa.springobservabilityjava.observability.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 路由指标注册表（进程内、无外部依赖）。
 *
 * <p>基数约束：只接受已经匹配到控制器路由模板的 key（由拦截器保证），key 集合上界为应用
 * 注册的路由数量；无法归属路由的请求统一计入固定桶 {@link #UNMAPPED_TEMPLATE}，
 * 绝不会按原始 URI/路径参数新建维度，避免基数爆炸污染指标。</p>
 */
public final class MetricsRegistry {

    /** 无法归属到路由模板的请求（如未匹配处理器的请求）使用的固定维度值。 */
    public static final String UNMAPPED_TEMPLATE = "UNMAPPED";

    private final ConcurrentMap<String, RouteMetrics> routes = new ConcurrentHashMap<>();
    private final RouteMetrics unmapped = new RouteMetrics(UNMAPPED_TEMPLATE);

    /** 按路由模板记录一次请求。{@code null} 或 {@link #UNMAPPED_TEMPLATE} 进入固定桶。 */
    public void record(String routeTemplate, long durationNanos, ErrorClass errorClass) {
        metricsFor(routeTemplate).record(durationNanos, errorClass);
    }

    /** 返回指定路由模板（含固定 UNMAPPED 桶）已有的指标；不存在则原子创建。 */
    public RouteMetrics metricsFor(String routeTemplate) {
        if (routeTemplate == null || UNMAPPED_TEMPLATE.equals(routeTemplate)) {
            return unmapped;
        }
        return routes.computeIfAbsent(routeTemplate, RouteMetrics::new);
    }

    /** 导出一致性快照（含 UNMAPPED 桶），供本地验证端点/测试读取。 */
    public List<RouteMetrics> snapshot() {
        List<RouteMetrics> all = new ArrayList<>(routes.size() + 1);
        all.addAll(routes.values());
        all.add(unmapped);
        return all;
    }
}
