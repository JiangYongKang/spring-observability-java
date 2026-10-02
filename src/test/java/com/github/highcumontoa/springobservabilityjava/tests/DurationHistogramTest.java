package com.github.highcumontoa.springobservabilityjava.tests;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.DurationBucket;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.ErrorClass;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.MetricsRegistry;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.RouteMetrics;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 耗时分布测试：
 * <ul>
 *   <li>固定档位边界归属唯一（0、各上界、上界 +1 纳秒）；</li>
 *   <li>档位集合固定有界（8 档），不随请求量增长；</li>
 *   <li>高并发下每个请求恰好落入一个档位：各档之和 = 总请求数，
 *       总耗时、最大值与逐笔记录自洽。</li>
 * </ul>
 */
class DurationHistogramTest {

    private static final Logger log = LoggerFactory.getLogger(DurationHistogramTest.class);

    private static final long MS = 1_000_000L;

    @Test
    void bucketCountIsFixedAndBounded() {
        assertEquals(8, DurationBucket.values().length, "histogram must keep a fixed bounded bucket set");
    }

    @Test
    void boundaryDurationsFallIntoExactlyOneBucket() {
        // 每档上界（含）落入本档，上界 +1 纳秒落入下一档，归属唯一无歧义
        assertEquals(DurationBucket.LE_1MS, DurationBucket.forNanos(0L));
        assertEquals(DurationBucket.LE_1MS, DurationBucket.forNanos(MS));
        assertEquals(DurationBucket.LE_5MS, DurationBucket.forNanos(MS + 1L));
        assertEquals(DurationBucket.LE_5MS, DurationBucket.forNanos(5 * MS));
        assertEquals(DurationBucket.LE_10MS, DurationBucket.forNanos(5 * MS + 1L));
        assertEquals(DurationBucket.LE_10MS, DurationBucket.forNanos(10 * MS));
        assertEquals(DurationBucket.LE_25MS, DurationBucket.forNanos(10 * MS + 1L));
        assertEquals(DurationBucket.LE_25MS, DurationBucket.forNanos(25 * MS));
        assertEquals(DurationBucket.LE_50MS, DurationBucket.forNanos(25 * MS + 1L));
        assertEquals(DurationBucket.LE_50MS, DurationBucket.forNanos(50 * MS));
        assertEquals(DurationBucket.LE_100MS, DurationBucket.forNanos(50 * MS + 1L));
        assertEquals(DurationBucket.LE_100MS, DurationBucket.forNanos(100 * MS));
        assertEquals(DurationBucket.LE_500MS, DurationBucket.forNanos(100 * MS + 1L));
        assertEquals(DurationBucket.LE_500MS, DurationBucket.forNanos(500 * MS));
        assertEquals(DurationBucket.GT_500MS, DurationBucket.forNanos(500 * MS + 1L));
        log.info("bucket boundary check: all 15 boundary samples map to a unique bucket");
    }

    @Test
    void histogramBinsAreExhaustiveAndSelfConsistentWithTotals() {
        MetricsRegistry registry = new MetricsRegistry();
        String route = "GET /api/items/{itemId}";

        // 逐笔记录，自行累计期望值：同一路由模板、不同路径参数（此处用不同耗时模拟不同请求）
        long[] durations = {
                0L, MS, MS + 1L, 5 * MS, 5 * MS + 1L, 10 * MS, 10 * MS + 1L,
                25 * MS, 25 * MS + 1L, 50 * MS, 50 * MS + 1L, 100 * MS,
                100 * MS + 1L, 500 * MS, 500 * MS + 1L, 600 * MS
        };
        long expectedTotal = 0L;
        long expectedMax = 0L;
        Map<DurationBucket, Long> expectedHistogram =
                new java.util.EnumMap<>(DurationBucket.class);
        for (DurationBucket bucket : DurationBucket.values()) {
            expectedHistogram.put(bucket, 0L);
        }
        for (long duration : durations) {
            registry.record(route, duration, ErrorClass.NONE);
            expectedTotal += duration;
            expectedMax = Math.max(expectedMax, duration);
            DurationBucket bucket = DurationBucket.forNanos(duration);
            expectedHistogram.merge(bucket, 1L, Long::sum);
        }

        RouteMetrics metrics = registry.metricsFor(route);
        assertEquals(durations.length, metrics.getCount());

        long histogramSum = 0L;
        for (DurationBucket bucket : DurationBucket.values()) {
            long count = metrics.getCount(bucket);
            histogramSum += count;
            assertEquals(expectedHistogram.get(bucket), count,
                    "bucket " + bucket + " count mismatch");
        }
        assertEquals(metrics.getCount(), histogramSum,
                "every request must land in exactly one bucket: bucket sum == total count");
        assertEquals(expectedTotal, metrics.getTotalNanos(), "total duration must match records");
        assertEquals(expectedMax, metrics.getMaxNanos(), "max duration must match records");
        log.info("histogram self-consistency check: count={}, histogramSum={}, totalNanos={}, maxNanos={}",
                metrics.getCount(), histogramSum, metrics.getTotalNanos(), metrics.getMaxNanos());
    }

    @Test
    void concurrentRequestsEachLandInExactlyOneBucket() throws InterruptedException {
        MetricsRegistry registry = new MetricsRegistry();
        String route = "POST /api/items/{itemId}/checkout";

        int threads = 16;
        int perThread = 2_000;
        // 覆盖各档位的固定耗时集合（含边界两侧），由线程按位轮流使用
        long[] palette = {
                0L, 1L, MS, MS + 1L, 5 * MS, 10 * MS + 1L, 25 * MS, 50 * MS + 1L,
                100 * MS, 500 * MS, 500 * MS + 1L, 1_000 * MS
        };

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int threadIndex = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        long duration = palette[(threadIndex * perThread + i) % palette.length];
                        ErrorClass errorClass = (i % 7 == 0) ? ErrorClass.SERVER_ERROR
                                : (i % 3 == 0) ? ErrorClass.CLIENT_ERROR : ErrorClass.NONE;
                        registry.record(route, duration, errorClass);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        RouteMetrics metrics = registry.metricsFor(route);
        long expected = (long) threads * perThread;
        assertEquals(expected, metrics.getCount());

        long histogramSum = 0L;
        for (DurationBucket bucket : DurationBucket.values()) {
            histogramSum += metrics.getCount(bucket);
        }
        assertEquals(expected, histogramSum,
                "under concurrency each request must land in exactly one bucket (no loss, no duplication)");

        long classSum = metrics.getCount(ErrorClass.NONE)
                + metrics.getCount(ErrorClass.CLIENT_ERROR)
                + metrics.getCount(ErrorClass.SERVER_ERROR);
        assertEquals(expected, classSum, "error-class counts must also sum to total");

        // 逐笔期望总耗时/最大值在提交侧同样计算一遍
        long expectedTotal = 0L;
        long expectedMax = 0L;
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < perThread; i++) {
                long duration = palette[(t * perThread + i) % palette.length];
                expectedTotal += duration;
                expectedMax = Math.max(expectedMax, duration);
            }
        }
        assertEquals(expectedTotal, metrics.getTotalNanos());
        assertEquals(expectedMax, metrics.getMaxNanos());

        log.info("concurrent histogram check: count={}, histogramSum={}, totalNanos={}, maxNanos={}",
                metrics.getCount(), histogramSum, metrics.getTotalNanos(), metrics.getMaxNanos());
    }
}
