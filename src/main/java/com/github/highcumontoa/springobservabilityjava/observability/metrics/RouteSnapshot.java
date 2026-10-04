package com.github.highcumontoa.springobservabilityjava.observability.metrics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 某条路由在某个<strong>已冻结</strong>统计批次（generation）中的数据视图。
 *
 * <p>"冻结"指该批次对<strong>新进入的请求</strong>关闭：重置之后进入的请求只会计入新批次。
 * 但重置之前已进入、重置之后才结束的在途请求，完成时仍计入这个旧批次——
 * 因此本类是对旧批次计数器的<strong>实时视图</strong>而非一次性拷贝：
 * 迟到的完成会反映到后续读取中，保证每笔请求恰好属于一个批次、任何一批都不漏算。</p>
 *
 * <p>注意：重置接口的<strong>响应体</strong>在返回那一刻序列化，是重置时刻的切面；
 * 之后才结束的在途请求不可能出现在已发出的响应体里，但会留存在本视图背后的旧批次数据中。</p>
 *
 * <p>底层计数器为 {@code LongAdder}/{@code AtomicLong}，读取本身线程安全，无需加锁。</p>
 */
public final class RouteSnapshot {

    private final long generation;
    private final RouteMetrics metrics;

    private RouteSnapshot(long generation, RouteMetrics metrics) {
        this.generation = generation;
        this.metrics = metrics;
    }

    /** 包装指定批次的实时指标为冻结视图。 */
    static RouteSnapshot from(long generation, RouteMetrics metrics) {
        return new RouteSnapshot(generation, metrics);
    }

    public long generation() {
        return generation;
    }

    public String route() {
        return metrics.getRouteTemplate();
    }

    public long count() {
        return metrics.getCount();
    }

    public long success() {
        return metrics.getCount(ErrorClass.NONE);
    }

    public long clientError() {
        return metrics.getCount(ErrorClass.CLIENT_ERROR);
    }

    public long serverError() {
        return metrics.getCount(ErrorClass.SERVER_ERROR);
    }

    public long totalDurationNanos() {
        return metrics.getTotalNanos();
    }

    public long maxDurationNanos() {
        return metrics.getMaxNanos();
    }

    public Map<String, Long> durationHistogram() {
        Map<String, Long> histogram = new LinkedHashMap<>();
        for (DurationBucket bucket : DurationBucket.values()) {
            histogram.put(bucket.label(), metrics.getCount(bucket));
        }
        return histogram;
    }
}
