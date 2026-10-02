package com.github.highcumontoa.springobservabilityjava.observability.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.ErrorClass;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.LatencyBucket;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.MetricsRegistry;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.RouteMetrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 进程内指标端点（本地验证用，无外部依赖）：
 * <ul>
 *   <li>{@code GET /internal/metrics}：当前统计批次的快照，维度只有路由模板与错误分类，
 *       基数有界；含固定耗时档位分布；</li>
 *   <li>{@code POST /internal/metrics/reset}：把统计起点重置到调用时刻，返回新批次信息。</li>
 * </ul>
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

    /**
     * 重置统计：返回后快照只包含重置之后进入的请求；与重置并发、在重置前进入的请求
     * 仍完整计入上一批次，不会丢失或混入新批次。
     */
    @PostMapping("/internal/metrics/reset")
    public Map<String, Object> reset() {
        MetricsRegistry.ResetResult result = registry.reset();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("generation", result.generation());
        view.put("startedAt", result.startedAt().toString());
        return view;
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
        view.put("latencyBuckets", latencyView(metrics));
        return view;
    }

    /** 固定档位视图：每档给出区间（纳秒）与计数；各档计数之和 = count。 */
    private static Map<String, Object> latencyView(RouteMetrics metrics) {
        Map<String, Object> buckets = new LinkedHashMap<>();
        for (LatencyBucket bucket : LatencyBucket.ordered()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("lowerInclusiveNanos", bucket.getLowerInclusiveNanos());
            entry.put("upperExclusiveNanos",
                    bucket.getUpperExclusiveNanos() == Long.MAX_VALUE
                            ? null : bucket.getUpperExclusiveNanos());
            entry.put("count", metrics.getBucketCount(bucket));
            buckets.put(bucket.getLabel(), entry);
        }
        return buckets;
    }
}
