package com.github.highcumontoa.springobservabilityjava.observability.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.ErrorClass;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.MetricsRegistry;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.RouteMetrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 进程内指标快照端点（本地验证用，无外部依赖）：{@code GET /internal/metrics}。
 * 维度只有路由模板与错误分类，基数有界。
 */
@RestController
public class MetricsController {

    private final MetricsRegistry registry;

    public MetricsController(MetricsRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/internal/metrics")
    public List<Map<String, Object>> metrics() {
        return registry.snapshot().stream().map(MetricsController::toView).toList();
    }

    private static Map<String, Object> toView(RouteMetrics metrics) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("route", metrics.getRouteTemplate());
        view.put("count", metrics.getCount());
        view.put("success", metrics.getCount(ErrorClass.NONE));
        view.put("clientError", metrics.getCount(ErrorClass.CLIENT_ERROR));
        view.put("serverError", metrics.getCount(ErrorClass.SERVER_ERROR));
        view.put("totalDurationNanos", metrics.getTotalNanos());
        view.put("maxDurationNanos", metrics.getMaxNanos());
        return view;
    }
}
