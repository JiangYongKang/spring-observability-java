package com.github.highcumontoa.springobservabilityjava.observability.metrics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 某条路由在某个统计批次（generation）<strong>冻结时刻</strong>的不可变快照，
 * 供重置接口返回上一批次的数据；冻结之后该批次不再变化。
 */
public record RouteSnapshot(
        long generation,
        String route,
        long count,
        long success,
        long clientError,
        long serverError,
        long totalDurationNanos,
        long maxDurationNanos,
        Map<String, Long> durationHistogram) {

    /** 在冻结时刻从实时指标拷贝全部值；LongAdder 的读取本身线程安全，无需加锁。 */
    static RouteSnapshot from(long generation, RouteMetrics metrics) {
        Map<String, Long> histogram = new LinkedHashMap<>();
        for (DurationBucket bucket : DurationBucket.values()) {
            histogram.put(bucket.label(), metrics.getCount(bucket));
        }
        return new RouteSnapshot(
                generation,
                metrics.getRouteTemplate(),
                metrics.getCount(),
                metrics.getCount(ErrorClass.NONE),
                metrics.getCount(ErrorClass.CLIENT_ERROR),
                metrics.getCount(ErrorClass.SERVER_ERROR),
                metrics.getTotalNanos(),
                metrics.getMaxNanos(),
                histogram);
    }
}
