package com.github.highcumontoa.springobservabilityjava.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.DurationBucket;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.ErrorClass;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.Generation;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.MetricsRegistry;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.RegistrySnapshot;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.RouteMetrics;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.RouteSnapshot;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统计重置测试：
 * <ul>
 *   <li>重置后从零累计，新批次与冻结的历史批次各自独立、互不干扰；</li>
 *   <li>批次归属按"进入时刻"判定：重置前已进入、重置后才结束的在途请求只留在旧批次，
 *       新批次只累计重置之后进入的请求；</li>
 *   <li>高并发"记录 + 重置"交错下不丢计数、不重复计数：
 *       所有冻结批次 + 当前批次的每类计数之和恰等于总记录次数。</li>
 * </ul>
 */
class MetricsResetTest {

    private static final Logger log = LoggerFactory.getLogger(MetricsResetTest.class);
    private static final String ROUTE = "GET /api/items/{itemId}";

    private long routeCount(RegistrySnapshot snapshot, String route) {
        return snapshot.routes().stream()
                .filter(r -> r.route().equals(route))
                .mapToLong(RouteSnapshot::count)
                .findFirst().orElse(0L);
    }

    @Test
    void resetStartsAnIndependentBatchAndFreezesHistory() {
        MetricsRegistry registry = new MetricsRegistry();
        assertEquals(0L, registry.currentGenerationId());

        registry.record(ROUTE, 1_000L, ErrorClass.NONE);
        registry.record(ROUTE, 2_000L, ErrorClass.CLIENT_ERROR);
        registry.record(null, 3_000L, ErrorClass.NONE); // 兜底桶也参与重置

        RegistrySnapshot gen0 = registry.reset();
        assertEquals(0L, gen0.generation());
        assertEquals(2L, routeCount(gen0, ROUTE));
        assertEquals(1L, gen0.routes().stream()
                .filter(r -> r.route().equals(MetricsRegistry.UNMAPPED_TEMPLATE))
                .mapToLong(RouteSnapshot::count).findFirst().orElseThrow());
        assertEquals(1L, registry.currentGenerationId());

        // 重置后当前批次必须从零开始，历史不参与新批次
        RouteMetrics fresh = registry.metricsFor(ROUTE);
        assertEquals(0L, fresh.getCount());
        assertEquals(0L, fresh.getTotalNanos());

        // 新批次只累计重置之后的记录
        registry.record(ROUTE, 4_000L, ErrorClass.SERVER_ERROR);
        assertEquals(1L, registry.metricsFor(ROUTE).getCount());
        assertEquals(1L, registry.metricsFor(ROUTE).getCount(ErrorClass.SERVER_ERROR));
        assertEquals(4_000L, registry.metricsFor(ROUTE).getTotalNanos());

        // 再次重置：冻结的是第 1 批（只有 1 笔），与第 0 批互不干扰
        RegistrySnapshot gen1 = registry.reset();
        assertEquals(1L, gen1.generation());
        assertEquals(1L, routeCount(gen1, ROUTE));
        assertEquals(2L, gen0.routes().size(), "older frozen batch must stay unchanged");
        assertEquals(2L, routeCount(gen0, ROUTE), "history batch must not be mutated by later resets");
        assertEquals(2L, registry.currentGenerationId());
        assertEquals(0L, registry.metricsFor(ROUTE).getCount(), "new batch starts from zero again");

        log.info("reset isolation check: gen0Count=2, gen1Count=1, currentGen={} count=0",
                registry.currentGenerationId());
    }

    @Test
    void inFlightRequestAtResetStaysInFrozenBatchAndOutOfNewBatch() {
        MetricsRegistry registry = new MetricsRegistry();

        // 一笔请求在重置之前进入（归属第 0 批），但迟迟不结束
        Generation inFlight = registry.currentBatch();
        // 重置前正常完成的一笔
        registry.record(ROUTE, 1_000L, ErrorClass.NONE);

        RegistrySnapshot frozen = registry.reset();
        assertEquals(0L, frozen.generation());
        assertEquals(1L, routeCount(frozen, ROUTE), "only the completed request is visible at reset time");

        // 重置之后才进入的请求：计入新批次
        registry.record(ROUTE, 2_000L, ErrorClass.NONE);
        // 在途请求在重置之后才结束：必须计入进入时归属的旧批次，而不是新批次
        registry.record(inFlight, ROUTE, 3_000L, ErrorClass.SERVER_ERROR);

        // 新批次：只含重置之后进入的那 1 笔，在途请求绝不混入
        RouteMetrics newBatch = registry.metricsFor(ROUTE);
        assertEquals(1L, newBatch.getCount(), "new batch must only count requests that entered after reset");
        assertEquals(2_000L, newBatch.getTotalNanos());
        assertEquals(0L, newBatch.getCount(ErrorClass.SERVER_ERROR),
                "in-flight request finished after reset must not leak into the new batch");

        // 冻结的旧批次：吸收重置前进入、重置后才结束的在途请求（实时视图反映迟到的完成）
        assertEquals(2L, routeCount(frozen, ROUTE),
                "frozen batch must absorb the in-flight request that entered before reset");
        assertEquals(4_000L, frozen.routes().stream()
                .filter(r -> r.route().equals(ROUTE))
                .mapToLong(RouteSnapshot::totalDurationNanos).findFirst().orElseThrow());
        assertEquals(1L, frozen.routes().stream()
                .filter(r -> r.route().equals(ROUTE))
                .mapToLong(RouteSnapshot::serverError).findFirst().orElseThrow());

        log.info("in-flight reset check: frozenCount=2 (1 completed + 1 late in-flight), "
                        + "newBatchCount=1 (entered after reset), currentGen={}",
                registry.currentGenerationId());
    }

    @Test
    void concurrentEntryAttributedRequestsAndResetsNeverLoseOrDuplicate() throws InterruptedException {
        MetricsRegistry registry = new MetricsRegistry();
        int requesterThreads = 12;
        int resetterThreads = 3;
        int requestsPerThread = 2_000;
        long totalRequests = (long) requesterThreads * requestsPerThread;

        ExecutorService pool = Executors.newFixedThreadPool(requesterThreads + resetterThreads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(requesterThreads + resetterThreads);
        List<RegistrySnapshot> frozenBatches = new ArrayList<>();
        AtomicLong recordedDurations = new AtomicLong();

        // 请求线程：模拟真实请求生命周期——进入时定归属，稍后完成时才记录
        for (int r = 0; r < requesterThreads; r++) {
            final int idx = r;
            pool.submit(() -> {
                try {
                    start.await();
                    long localDuration = 0L;
                    for (int i = 0; i < requestsPerThread; i++) {
                        Generation enteredAt = registry.currentBatch(); // 进入时刻定归属
                        Thread.yield(); // 拉大"进入→完成"窗口，让重置穿插其间
                        long duration = (i % DurationBucket.values().length + 1) * 1_000_000L;
                        ErrorClass errorClass = (idx + i) % 5 == 0 ? ErrorClass.SERVER_ERROR
                                : (idx + i) % 3 == 0 ? ErrorClass.CLIENT_ERROR : ErrorClass.NONE;
                        registry.record(enteredAt, ROUTE, duration, errorClass); // 完成时计入进入时的批次
                        localDuration += duration;
                    }
                    recordedDurations.addAndGet(localDuration);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        for (int z = 0; z < resetterThreads; z++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 150; i++) {
                        synchronized (frozenBatches) {
                            frozenBatches.add(registry.reset());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "workload should finish in time");
        pool.shutdown();
        // 所有请求线程都已结束（不存在仍在途的请求），做最后一次冻结收口当前批次
        synchronized (frozenBatches) {
            frozenBatches.add(registry.reset());
        }

        // 总账：冻结批次是实时视图，迟到的在途完成也已落账；
        // 所有批次该路由的计数之和必须恰等于请求总数——不漏、不重、每笔恰好一批
        long countAcrossBatches = 0;
        long successAcrossBatches = 0;
        long clientAcrossBatches = 0;
        long serverAcrossBatches = 0;
        long durationAcrossBatches = 0;
        long histogramAcrossBatches = 0;
        List<Long> batchGenerations = new ArrayList<>();
        for (RegistrySnapshot batch : frozenBatches) {
            batchGenerations.add(batch.generation());
            for (RouteSnapshot route : batch.routes()) {
                if (route.route().equals(ROUTE)) {
                    countAcrossBatches += route.count();
                    successAcrossBatches += route.success();
                    clientAcrossBatches += route.clientError();
                    serverAcrossBatches += route.serverError();
                    durationAcrossBatches += route.totalDurationNanos();
                    histogramAcrossBatches +=
                            route.durationHistogram().values().stream().mapToLong(Long::longValue).sum();
                }
            }
        }

        assertEquals(totalRequests, countAcrossBatches,
                "entry-attributed requests interleaved with resets must neither be lost nor duplicated");
        assertEquals(totalRequests,
                successAcrossBatches + clientAcrossBatches + serverAcrossBatches,
                "per-class counts across batches must sum to total");
        assertEquals(totalRequests, histogramAcrossBatches,
                "histogram bins across batches must sum to total as well");
        assertEquals(recordedDurations.get(), durationAcrossBatches,
                "total duration across batches must match recorded durations");

        long distinctGenerations = batchGenerations.stream().distinct().count();
        assertEquals(batchGenerations.size(), distinctGenerations, "generation ids must be unique");
        assertEquals(0L, batchGenerations.stream().min(Long::compareTo).orElseThrow());
        assertEquals(batchGenerations.size() - 1L,
                batchGenerations.stream().max(Long::compareTo).orElseThrow(),
                "generation ids must be contiguous 0..N");

        log.info("entry-attributed concurrent reset check: totalRequests={}, batches={}, "
                        + "countAcrossBatches={}, durationAcrossBatches={}",
                totalRequests, frozenBatches.size(), countAcrossBatches, durationAcrossBatches);
    }

    @Test
    void concurrentResetsAndRecordsNeverLoseOrDuplicateCounts() throws InterruptedException {
        MetricsRegistry registry = new MetricsRegistry();
        int recorderThreads = 12;
        int resetterThreads = 3;
        int recordsPerThread = 3_000;
        long totalRecords = (long) recorderThreads * recordsPerThread;

        ExecutorService pool = Executors.newFixedThreadPool(recorderThreads + resetterThreads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(recorderThreads + resetterThreads);
        List<RegistrySnapshot> frozenBatches = new ArrayList<>();
        AtomicLong recordedDurations = new AtomicLong();

        for (int r = 0; r < recorderThreads; r++) {
            final int idx = r;
            pool.submit(() -> {
                try {
                    start.await();
                    long localDuration = 0L;
                    for (int i = 0; i < recordsPerThread; i++) {
                        long duration = (i % DurationBucket.values().length + 1) * 1_000_000L;
                        ErrorClass errorClass = (idx + i) % 5 == 0 ? ErrorClass.SERVER_ERROR
                                : (idx + i) % 3 == 0 ? ErrorClass.CLIENT_ERROR : ErrorClass.NONE;
                        registry.record(ROUTE, duration, errorClass);
                        localDuration += duration;
                    }
                    recordedDurations.addAndGet(localDuration);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        for (int z = 0; z < resetterThreads; z++) {
            pool.submit(() -> {
                try {
                    start.await();
                    // 一边反复重置；冻结下来的批次收集起来参与总账核对
                    for (int i = 0; i < 200; i++) {
                        synchronized (frozenBatches) {
                            frozenBatches.add(registry.reset());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "workload should finish in time");
        pool.shutdown();
        // 重置者停止后做最后一次冻结，确保所有记录都落入某个被统计到的批次
        RegistrySnapshot finalBatch = registry.reset();
        synchronized (frozenBatches) {
            frozenBatches.add(finalBatch);
        }

        // 总账：所有批次（冻结 + 最后一次冻结）中该路由的计数之和必须恰等于记录次数
        long countAcrossBatches = 0;
        long successAcrossBatches = 0;
        long clientAcrossBatches = 0;
        long serverAcrossBatches = 0;
        long durationAcrossBatches = 0;
        long histogramAcrossBatches = 0;
        List<Long> batchGenerations = new ArrayList<>();
        for (RegistrySnapshot batch : frozenBatches) {
            batchGenerations.add(batch.generation());
            for (RouteSnapshot route : batch.routes()) {
                if (route.route().equals(ROUTE)) {
                    countAcrossBatches += route.count();
                    successAcrossBatches += route.success();
                    clientAcrossBatches += route.clientError();
                    serverAcrossBatches += route.serverError();
                    durationAcrossBatches += route.totalDurationNanos();
                    histogramAcrossBatches +=
                            route.durationHistogram().values().stream().mapToLong(Long::longValue).sum();
                }
            }
        }

        assertEquals(totalRecords, countAcrossBatches,
                "concurrent resets must neither lose nor duplicate any request");
        assertEquals(totalRecords,
                successAcrossBatches + clientAcrossBatches + serverAcrossBatches,
                "per-class counts across batches must sum to total");
        assertEquals(totalRecords, histogramAcrossBatches,
                "histogram bins across batches must sum to total as well");
        assertEquals(recordedDurations.get(), durationAcrossBatches,
                "total duration across batches must match recorded durations");

        // 批次序号连续不重复
        long distinctGenerations = batchGenerations.stream().distinct().count();
        assertEquals(batchGenerations.size(), distinctGenerations, "generation ids must be unique");
        assertEquals(0L, batchGenerations.stream().min(Long::compareTo).orElseThrow());
        assertEquals(batchGenerations.size() - 1L,
                batchGenerations.stream().max(Long::compareTo).orElseThrow(),
                "generation ids must be contiguous 0..N");

        log.info("concurrent reset check: totalRecords={}, batches={}, countAcrossBatches={}",
                totalRecords, frozenBatches.size(), countAcrossBatches);
    }
}
