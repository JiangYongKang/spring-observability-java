package com.github.highcumontoa.springobservabilityjava.tests;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.ErrorClass;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.MetricsGeneration;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.MetricsRegistry;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.RouteMetrics;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统计重置语义与并发边界单测：
 * <ul>
 *   <li>重置后从零累计，历史批次数据不出现在新快照中；</li>
 *   <li>请求钉取进入时代次：重置前进入的请求只计入老批次，重置后进入的只计入新批次；</li>
 *   <li>重置与在途请求高并发交错时不丢计数、不重复计数、不跨批混入；</li>
 *   <li>多次连续重置彼此隔离，代次序号单调。</li>
 * </ul>
 */
class MetricsResetTest {

    private static final Logger log = LoggerFactory.getLogger(MetricsResetTest.class);
    private static final String TEMPLATE = "GET /api/items/{itemId}";

    @Test
    void resetStartsIndependentBatchAndKeepsHistorySeparate() {
        MetricsRegistry registry = new MetricsRegistry();
        assertEquals(0L, registry.currentGenerationNumber());

        registry.record(TEMPLATE, 1_000L, ErrorClass.NONE);
        registry.record(TEMPLATE, 2_000L, ErrorClass.CLIENT_ERROR);
        RouteMetrics before = registry.metricsFor(TEMPLATE);
        assertEquals(2L, before.getCount());
        assertEquals(3_000L, before.getTotalNanos());

        MetricsRegistry.ResetResult reset = registry.reset();
        assertEquals(1L, reset.generation());
        assertEquals(1L, registry.currentGenerationNumber());

        // 重置后从零开始
        RouteMetrics after = registry.metricsFor(TEMPLATE);
        assertEquals(0L, after.getCount(), "new batch must start at zero");
        assertEquals(0L, after.getTotalNanos());
        assertEquals(0L, after.getMaxNanos());
        assertEquals(0L, after.getBucketCountSum());

        registry.record(TEMPLATE, 5_000L, ErrorClass.NONE);
        assertEquals(1L, registry.metricsFor(TEMPLATE).getCount());
        assertEquals(5_000L, registry.metricsFor(TEMPLATE).getTotalNanos());
        // 老批次实例保持冻结值，不受新批次写入影响
        assertEquals(2L, before.getCount());
        assertEquals(3_000L, before.getTotalNanos());

        log.info("reset isolation check: beforeCount={}, afterCount={}, generation={}",
                before.getCount(), after.getCount(), registry.currentGenerationNumber());
    }

    @Test
    void requestInFlightAtResetIsNeitherLostNorMovedToNewBatch() throws Exception {
        MetricsRegistry registry = new MetricsRegistry();

        // 模拟一个在重置"之前"已进入、被钉在老代次、但重置时仍未完成的请求
        MetricsGeneration oldGeneration = registry.acquire();
        assertEquals(0L, oldGeneration.getGeneration());

        // 重置线程与该在途请求并发：reset 必须等到它结清
        final long[] observedNewGeneration = {0L};
        Thread reseter = new Thread(() -> {
            MetricsRegistry.ResetResult result = registry.reset();
            observedNewGeneration[0] = result.generation();
        });
        reseter.start();

        // 给 reset 一点时间进入等待
        Thread.sleep(100L);
        assertTrue(reseter.isAlive(), "reset must wait while pre-reset request is in flight");

        // 老请求此刻才完成记录：必须落进老批次，且耗时真实
        registry.record(oldGeneration, TEMPLATE, 42_000_000L, ErrorClass.SERVER_ERROR);
        registry.release(oldGeneration);

        reseter.join(5_000L);
        assertEquals(1L, observedNewGeneration[0], "reset should complete after in-flight drain");

        // 新批次不含老请求
        RouteMetrics newBatch = registry.metricsFor(TEMPLATE);
        assertEquals(0L, newBatch.getCount(), "pre-reset request must not appear in new batch");
        assertEquals(0L, newBatch.getCount(ErrorClass.SERVER_ERROR));

        log.info("in-flight drain check: newBatchCount={}, newGeneration={}",
                newBatch.getCount(), observedNewGeneration[0]);
    }

    @Test
    void concurrentResetsAndRequestsNeverLoseOrDuplicateCounts() throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        int requestThreads = 12;
        int requestsPerThread = 1_000;
        int resetCount = 20;
        ExecutorService pool = Executors.newFixedThreadPool(requestThreads + 1);
        CountDownLatch start = new CountDownLatch(1);

        // 请求线程：每次都走 acquire/release 完整在途协议，把代次钉取暴露在并发重置下
        for (int t = 0; t < requestThreads; t++) {
            final int seed = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < requestsPerThread; i++) {
                        MetricsGeneration generation = registry.acquire();
                        try {
                            registry.record(generation, TEMPLATE,
                                    1_000L + ((i + seed) % 7), ErrorClass.NONE);
                        } finally {
                            registry.release(generation);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        // 重置线程：不断切批次
        for (int r = 0; r < resetCount; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    registry.reset();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "workload must finish");

        int totalRequests = requestThreads * requestsPerThread;

        // 所有工作线程已结束（请求线程的 release 与重置线程的 reset 均已完成）。
        // 此刻跨存活代次做全局守恒：总计数必须精确等于请求总数——不丢、不重、不串批。
        long generationAtQuiescence = registry.currentGenerationNumber();
        assertEquals(totalRequests, registry.totalCountAcrossLiveGenerations(TEMPLATE),
                "every request must be counted exactly once across reset boundaries");
        assertTrue(generationAtQuiescence >= 1L, "at least one reset must have advanced generation");

        // 再做一次重置：返回后当前批次从零开始，历史（含上面所有批次）不再出现
        registry.reset();
        RouteMetrics current = registry.metricsFor(TEMPLATE);
        assertEquals(0L, current.getCount(), "post-reset batch must start from zero");
        assertEquals(0L, current.getBucketCountSum());

        log.info("concurrent reset check: totalRequests={}, generationAtQuiescence={}, finalGeneration={}",
                totalRequests, generationAtQuiescence, registry.currentGenerationNumber());
    }
}
