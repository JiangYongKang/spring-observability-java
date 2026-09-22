package com.github.highcumontoa.springobservabilityjava.metric;

import com.github.highcumontoa.springobservabilityjava.error.ErrorCategory;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/**
 * 路由指标注册表。
 *
 * <p>基数约束（明确取舍）：
 * <ul>
 *   <li>维度只取 {@code HTTP 方法 + 路由模板 + 错误分类}，路径参数已被模板归一，
 *       因此维度数 ≈ 路由数 × 分类数（3），天然有界；</li>
 *   <li>绝不使用原始 URI / 查询串作为维度，未匹配到路由模板的请求不记录，
 *       因此不存在“每个参数值一个维度”的膨胀；</li>
 *   <li>另设硬性上限 {@value #MAX_CARDINALITY}：超出的新维度被丢弃并计入
 *       {@code droppedRecords}，保护内存而不是悄悄建维度。</li>
 * </ul>
 */
@Component
public class MetricsRegistry {

    static final int MAX_CARDINALITY = 200;

    private final ConcurrentHashMap<RouteMetricKey, RouteMetricAggregate> aggregates =
            new ConcurrentHashMap<>();
    private final LongAdder droppedRecords = new LongAdder();

    public void record(RouteMetricKey key, long durationNanos) {
        RouteMetricAggregate aggregate = aggregates.get(key);
        if (aggregate == null) {
            aggregate = createIfWithinCardinality(key);
            if (aggregate == null) {
                droppedRecords.increment();
                return;
            }
        }
        aggregate.record(durationNanos);
    }

    private RouteMetricAggregate createIfWithinCardinality(RouteMetricKey key) {
        // computeIfAbsence 的创建与基数检查需在同一临界区，避免并发突破上限
        synchronized (aggregates) {
            if (aggregates.size() >= MAX_CARDINALITY) {
                return null;
            }
            return aggregates.computeIfAbsent(key, RouteMetricAggregate::new);
        }
    }

    /** 返回稳定排序的快照，便于断言与展示。 */
    public List<RouteMetricAggregate> snapshot() {
        return aggregates.values().stream()
                .sorted(Comparator
                        .comparing((RouteMetricAggregate a) -> a.getKey().routeTemplate())
                        .thenComparing(a -> a.getKey().method())
                        .thenComparing(a -> a.getKey().errorCategory().name()))
                .toList();
    }

    public RouteMetricAggregate find(String method, String routeTemplate, ErrorCategory category) {
        return aggregates.get(new RouteMetricKey(method, routeTemplate, category));
    }

    public int cardinality() {
        return aggregates.size();
    }

    public long droppedCount() {
        return droppedRecords.sum();
    }

    public void reset() {
        aggregates.clear();
        droppedRecords.reset();
    }
}
