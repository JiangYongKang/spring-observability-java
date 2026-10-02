package com.github.highcumontoa.springobservabilityjava.tests;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.ErrorClass;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.LatencyBucket;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.MetricsRegistry;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.RouteMetrics;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 耗时分布单测：
 * <ul>
 *   <li>分档区间不重不漏，每个边界值归属唯一档位；</li>
 *   <li>高并发下每个请求恰好落入一个档位，各档计数之和精确等于总请求数；</li>
 *   <li>各档计数之和、总耗时、最大值三者与全部写入自洽；</li>
 *   <li>不同路径参数值归入同一路由模板的同一份分布（不随参数拆桶）。</li>
 * </ul>
 */
class LatencyHistogramTest {

    private static final Logger log = LoggerFactory.getLogger(LatencyHistogramTest.class);

    @Test
    void bucketsPartitionNonNegativeDurationDomainWithoutGapsOrOverlap() {
        // 关键边界：0、每档下界（恰等于下界应落入本档）、末档长尾
        long[] probes = {
                0L,
                999_999L, 1_000_000L,
                4_999_999L, 5_000_000L,
                9_999_999L, 10_000_000L,
                24_999_999L, 25_000_000L,
                49_999_999L, 50_000_000L,
                99_999_999L, 100_000_000L,
                999_999_999L, 1_000_000_000L,
                60_000_000_000L, Long.MAX_VALUE
        };
        for (long probe : probes) {
            LatencyBucket bucket = LatencyBucket.bucketOfNanos(probe);
            assertTrue(probe >= bucket.getLowerInclusiveNanos(),
                    "duration " + probe + " must be >= lower bound of " + bucket);
            if (bucket.getUpperExclusiveNanos() != Long.MAX_VALUE) {
                assertTrue(probe < bucket.getUpperExclusiveNanos(),
                        "duration " + probe + " must be < upper bound of " + bucket);
            }
        }
        // 区间首尾相接：上一档上界 = 下一档下界
        LatencyBucket[] all = LatencyBucket.values();
        for (int i = 1; i < all.length; i++) {
            assertEquals(all[i - 1].getUpperExclusiveNanos(), all[i].getLowerInclusiveNanos(),
                    "adjacent buckets must be contiguous at index " + i);
        }
        assertEquals(0L, all[0].getLowerInclusiveNanos());
        assertEquals(Long.MAX_VALUE, all[all.length - 1].getUpperExclusiveNanos());

        // 恰好等于各档下界的值落入本档而非上一档
        assertEquals(LatencyBucket.LT_1MS, LatencyBucket.bucketOfNanos(0L));
        assertEquals(LatencyBucket.LT_5MS, LatencyBucket.bucketOfNanos(1_000_000L));
        assertEquals(LatencyBucket.GE_1000MS, LatencyBucket.bucketOfNanos(1_000_000_000L));
    }

    @Test
    void concurrentRequestsEachLandInExactlyOneBucketAndTotalsAreConsistent()
            throws InterruptedException {
        MetricsRegistry registry = new MetricsRegistry();
        String template = "GET /api/items/{itemId}";

        // 每种档位放一个代表性耗时（取区间中点；末档给大值），各线程固定打一种
        long[] representativeDurations = {
                500_000L,
                2_000_000L,
                7_000_000L,
                15_000_000L,
                30_000_000L,
                70_000_000L,
                500_000_000L,
                2_000_000_000L
        };
        int threads = representativeDurations.length;
        int perThread = 2_500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final long duration = representativeDurations[t];
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        // 所有写入只使用路由模板（真实链路上不同路径参数也解析为同一模板）
                        registry.record(template, duration,
                                (i % 3 == 0) ? ErrorClass.CLIENT_ERROR : ErrorClass.NONE);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();

        RouteMetrics metrics = registry.metricsFor(template);
        long expected = (long) threads * perThread;

        // 1) 各档之和 == 总数：每请求恰好落一档，不丢不重
        assertEquals(expected, metrics.getCount());
        assertEquals(expected, metrics.getBucketCountSum(),
                "sum of all latency buckets must equal total request count");

        // 2) 按预期档位逐项核对（每档恰好 perThread 次）
        Map<LatencyBucket, Long> expectedByBucket = new HashMap<>();
        for (long duration : representativeDurations) {
            expectedByBucket.merge(LatencyBucket.bucketOfNanos(duration),
                    (long) perThread, Long::sum);
        }
        for (LatencyBucket bucket : LatencyBucket.values()) {
            long expectedCount = expectedByBucket.getOrDefault(bucket, 0L);
            assertEquals(expectedCount, metrics.getBucketCount(bucket),
                    "bucket " + bucket + " count mismatch");
        }

        // 3) 总耗时与最大值与全部写入自洽
        long expectedTotal = 0L;
        long expectedMax = 0L;
        for (long duration : representativeDurations) {
            expectedTotal += duration * perThread;
            expectedMax = Math.max(expectedMax, duration);
        }
        assertEquals(expectedTotal, metrics.getTotalNanos());
        assertEquals(expectedMax, metrics.getMaxNanos());

        // 4) 错误分类分项之和同样等于总数（成功/失败也不重不漏）
        assertEquals(expected,
                metrics.getCount(ErrorClass.NONE)
                        + metrics.getCount(ErrorClass.CLIENT_ERROR)
                        + metrics.getCount(ErrorClass.SERVER_ERROR));

        log.info("histogram consistency check: route={}, count={}, bucketSum={}, totalNanos={}, maxNanos={}",
                metrics.getRouteTemplate(), metrics.getCount(), metrics.getBucketCountSum(),
                metrics.getTotalNanos(), metrics.getMaxNanos());
    }
}
