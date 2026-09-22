package com.github.highcumontoa.springobservabilityjava.metric;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 本地可观测端点：查询/重置聚合指标，不依赖任何外部系统。 */
@RestController
@RequestMapping("/internal/metrics")
public class MetricsController {

    private final MetricsRegistry registry;

    public MetricsController(MetricsRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public Map<String, Object> snapshot() {
        List<Map<String, Object>> metrics = registry.snapshot().stream()
                .map(MetricsController::toView)
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cardinality", registry.cardinality());
        result.put("maxCardinality", MetricsRegistry.MAX_CARDINALITY);
        result.put("droppedRecords", registry.droppedCount());
        result.put("metrics", metrics);
        return result;
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        registry.reset();
        return Map.of("reset", true);
    }

    private static Map<String, Object> toView(RouteMetricAggregate aggregate) {
        RouteMetricKey key = aggregate.getKey();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("method", key.method());
        view.put("routeTemplate", key.routeTemplate());
        view.put("errorCategory", key.errorCategory().name());
        view.put("count", aggregate.getCount());
        view.put("totalNanos", aggregate.getTotalNanos());
        view.put("minNanos", aggregate.getMinNanos());
        view.put("maxNanos", aggregate.getMaxNanos());
        view.put("avgNanos", Math.round(aggregate.getAverageNanos()));
        return view;
    }
}
