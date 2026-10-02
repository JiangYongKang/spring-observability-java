package com.github.highcumontoa.springobservabilityjava.observability.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.DurationBucket;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.ErrorClass;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.MetricsRegistry;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.RegistrySnapshot;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.RouteMetrics;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.RouteSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 进程内指标端点（本地验证用，无外部依赖）：
 * <ul>
 *   <li>{@code GET /internal/metrics}：当前批次的实时快照；</li>
 *   <li>{@code POST /internal/metrics/reset}：把统计起点原子重置到当前时刻，
 *       返回被冻结的上一批次数据。</li>
 * </ul>
 * 维度只有路由模板、错误分类与固定耗时档位，基数有界。
 */
@RestController
public class MetricsController {

    private final MetricsRegistry registry;

    public MetricsController(MetricsRegistry registry) {
        this.registry = registry;
    }

    /** 当前批次视图：携带批次序号，重置后序号 +1，便于核对"这一批"。 */
    @GetMapping("/internal/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("generation", registry.currentGenerationId());
        view.put("routes", registry.snapshot().stream().map(MetricsController::toLiveView).toList());
        return view;
    }

    /**
     * 重置统计：之后的请求在全新批次中从零累计；响应体是重置前那一批的最终冻结快照，
     * 其中 {@code nextGeneration} 为重置后开始累计的新批次序号。
     */
    @PostMapping("/internal/metrics/reset")
    public Map<String, Object> reset() {
        RegistrySnapshot frozen = registry.reset();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("generation", frozen.generation());
        view.put("startedAtEpochMillis", frozen.startedAtEpochMillis());
        view.put("resetAtEpochMillis", frozen.resetAtEpochMillis());
        view.put("nextGeneration", frozen.generation() + 1);
        view.put("routes", frozen.routes().stream().map(MetricsController::toFrozenView).toList());
        return view;
    }

    private static Map<String, Object> toLiveView(RouteMetrics metrics) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("route", metrics.getRouteTemplate());
        view.put("count", metrics.getCount());
        view.put("success", metrics.getCount(ErrorClass.NONE));
        view.put("clientError", metrics.getCount(ErrorClass.CLIENT_ERROR));
        view.put("serverError", metrics.getCount(ErrorClass.SERVER_ERROR));
        view.put("totalDurationNanos", metrics.getTotalNanos());
        view.put("maxDurationNanos", metrics.getMaxNanos());
        view.put("durationHistogram", histogramView(metrics));
        return view;
    }

    private static Map<String, Object> toFrozenView(RouteSnapshot snapshot) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("route", snapshot.route());
        view.put("count", snapshot.count());
        view.put("success", snapshot.success());
        view.put("clientError", snapshot.clientError());
        view.put("serverError", snapshot.serverError());
        view.put("totalDurationNanos", snapshot.totalDurationNanos());
        view.put("maxDurationNanos", snapshot.maxDurationNanos());
        view.put("durationHistogram", snapshot.durationHistogram());
        return view;
    }

    private static Map<String, Long> histogramView(RouteMetrics metrics) {
        Map<String, Long> histogram = new LinkedHashMap<>();
        for (DurationBucket bucket : DurationBucket.values()) {
            histogram.put(bucket.label(), metrics.getCount(bucket));
        }
        return histogram;
    }
}
