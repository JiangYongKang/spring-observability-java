package com.github.highcumontoa.springobservabilityjava.tests;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.ErrorClass;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.MetricsRegistry;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.RouteMetrics;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标并发单元测试：高并发写入下计数与请求总数一致；无法归属路由的请求不产生新维度。
 */
class RouteMetricsConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(RouteMetricsConcurrencyTest.class);

    @Test
    void concurrentWritesAreNeitherLostNorDuplicated() throws InterruptedException {
        MetricsRegistry registry = new MetricsRegistry();
        String template = "GET /api/items/{itemId}";

        int threads = 16;
        int perThread = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final ErrorClass errorClass = (t % 4 == 0) ? ErrorClass.CLIENT_ERROR
                    : (t % 5 == 0) ? ErrorClass.SERVER_ERROR : ErrorClass.NONE;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        registry.record(template, 1_000L, errorClass);
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

        RouteMetrics metrics = registry.metricsFor(template);
        long expected = (long) threads * perThread;
        assertEquals(expected, metrics.getCount(), "total count must equal total requests");
        assertEquals(expected,
                metrics.getCount(ErrorClass.NONE)
                        + metrics.getCount(ErrorClass.CLIENT_ERROR)
                        + metrics.getCount(ErrorClass.SERVER_ERROR),
                "per-class counts must sum exactly to total (no loss, no duplication)");
        assertEquals(expected * 1_000L, metrics.getTotalNanos());
        assertEquals(1_000L, metrics.getMaxNanos());

        List<RouteMetrics> snapshot = registry.snapshot();
        assertEquals(2, snapshot.size(), "only one route template plus fixed UNMAPPED bucket");

        log.info("concurrency metrics check: route={}, count={}, ok={}, clientErr={}, serverErr={}",
                metrics.getRouteTemplate(), metrics.getCount(),
                metrics.getCount(ErrorClass.NONE), metrics.getCount(ErrorClass.CLIENT_ERROR),
                metrics.getCount(ErrorClass.SERVER_ERROR));
    }

    @Test
    void unmappedAndUnknownPathsNeverCreateNewDimensions() {
        MetricsRegistry registry = new MetricsRegistry();
        registry.record(null, 1L, ErrorClass.NONE);
        registry.record(MetricsRegistry.UNMAPPED_TEMPLATE, 1L, ErrorClass.NONE);
        registry.record(null, 1L, ErrorClass.NONE);

        List<RouteMetrics> snapshot = registry.snapshot();
        assertEquals(1, snapshot.size(), "only the fixed UNMAPPED bucket must exist");
        assertEquals(3, snapshot.get(0).getCount());
        log.info("cardinality check: route={}, count={}",
                snapshot.get(0).getRouteTemplate(), snapshot.get(0).getCount());
    }
}
