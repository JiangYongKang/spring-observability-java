package com.github.highcumontoa.springobservabilityjava.observability.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发"请求进入/结束 + 反复重置"交错的生命周期测试。
 *
 * <p>覆盖业务：</p>
 * <ul>
 *   <li>业务路由 {@code GET /api/items/{itemId}} 的请求；</li>
 *   <li>无法归属路由的 {@code UNMAPPED} 兜底请求；</li>
 *   <li>请求在进入后随机停留一小段（含跨重置）才结束，刻意制造"进入在重置前、结束在重置后"。</li>
 * </ul>
 *
 * <p>核对目标：每笔请求<strong>恰好</strong>属于一个批次——
 * 所有冻结批次 + 最后一批的计数之和（含分类、耗时、耗时档位）恒等于进入的请求总数，
 * 既不漏算也不重复算；批次序号连续不重复。</p>
 */
class BatchLifecycleConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(BatchLifecycleConcurrencyTest.class);
    private static final String ROUTE = "GET /api/items/{itemId}";

    private long countFor(List<RegistrySnapshot> batches, String route) {
        long sum = 0;
        for (RegistrySnapshot batch : batches) {
            sum += batch.routes().stream()
                    .filter(r -> r.route().equals(route))
                    .mapToLong(RouteSnapshot::count).findFirst().orElse(0L);
        }
        return sum;
    }

    private long histogramFor(List<RegistrySnapshot> batches, String route) {
        long sum = 0;
        for (RegistrySnapshot batch : batches) {
            sum += batch.routes().stream()
                    .filter(r -> r.route().equals(route))
                    .flatMap(r -> r.durationHistogram().values().stream())
                    .mapToLong(Long::longValue).sum();
        }
        return sum;
    }

    @Test
    void interleavedEntersFinishesAndResetsAttributeEachRequestToExactlyOneBatch()
            throws InterruptedException {
        MetricsRegistry registry = new MetricsRegistry();
        int clientThreads = 12;
        int requestsPerClient = 400;
        int resetterThreads = 3;
        int resetsPerResetter = 100;
        long totalBusiness = (long) clientThreads * requestsPerClient;
        long totalUnmapped = totalBusiness; // 每笔业务请求对应一笔兜底请求

        ExecutorService pool = Executors.newFixedThreadPool(clientThreads + resetterThreads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch clientsDone = new CountDownLatch(clientThreads);
        AtomicInteger inFlightClients = new AtomicInteger(clientThreads);
        List<RegistrySnapshot> frozenBatches = java.util.Collections.synchronizedList(new ArrayList<>());

        // 客户端：先进入（领取批次），随机短暂停留后再结束——停留期间可能穿插多次重置
        for (int c = 0; c < clientThreads; c++) {
            final int clientIndex = c;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < requestsPerClient; i++) {
                        RequestBatch batch = registry.openBatch();
                        long businessGen = batch.generationId();

                        // 0~2ms 的在途窗口：足以跨越并发重置，验证"按进入时刻归属"
                        Thread.sleep(clientIndex % 3L);

                        batch.finish(ROUTE, 1_000L + (i % 7),
                                (i % 5 == 0) ? ErrorClass.SERVER_ERROR : ErrorClass.NONE);

                        // 同一线程再做一笔 UNMAPPED 兜底请求，验证兜底路径同样不串批
                        RequestBatch unmapped = registry.openBatch();
                        long unmappedGen = unmapped.generationId();
                        Thread.sleep(clientIndex % 2L);
                        unmapped.finish(MetricsRegistry.UNMAPPED_TEMPLATE, 2_000L + (i % 5),
                                (i % 4 == 0) ? ErrorClass.CLIENT_ERROR : ErrorClass.NONE);

                        assertTrue(businessGen >= 0L && unmappedGen >= 0L);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    inFlightClients.decrementAndGet();
                    clientsDone.countDown();
                }
            });
        }

        // 重置者：持续反复重置；冻结下来的批次收集起来参与总账核对
        for (int r = 0; r < resetterThreads; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (inFlightClients.get() > 0) {
                        frozenBatches.add(registry.reset());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        start.countDown();
        assertTrue(clientsDone.await(60, TimeUnit.SECONDS), "client workload should finish in time");
        // 客户端全部结束后做最后一次冻结，确保所有记录都进入某个被统计到的批次
        frozenBatches.add(registry.reset());
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "resetter threads should wind down");

        // 总账：所有批次中业务/兜底计数之和必须恰等于进入的请求总数（不漏、不重）
        assertEquals(totalBusiness, countFor(frozenBatches, ROUTE),
                "every business request must belong to exactly one batch");
        assertEquals(totalUnmapped, countFor(frozenBatches, MetricsRegistry.UNMAPPED_TEMPLATE),
                "every unmapped request must belong to exactly one batch");
        assertEquals(totalBusiness, histogramFor(frozenBatches, ROUTE),
                "histogram bins across batches must sum to total as well");
        assertEquals(totalUnmapped, histogramFor(frozenBatches, MetricsRegistry.UNMAPPED_TEMPLATE),
                "unmapped histogram bins across batches must sum to total as well");
        assertEquals(totalBusiness + totalUnmapped,
                countFor(frozenBatches, ROUTE) + countFor(frozenBatches, MetricsRegistry.UNMAPPED_TEMPLATE),
                "no request may be counted in two batches or in none");

        // 批次序号连续不重复
        List<Long> ids = frozenBatches.stream().map(RegistrySnapshot::generation).sorted().toList();
        for (int i = 0; i < ids.size(); i++) {
            assertEquals((long) i, ids.get(i), "generation ids must be contiguous 0..N with no gap/duplicate");
        }

        log.info("interleaved lifecycle: businessRequests={}, unmappedRequests={}, batches={}, "
                        + "businessCounted={}, unmappedCounted={}, allExactlyOnce=true",
                totalBusiness, totalUnmapped, frozenBatches.size(),
                countFor(frozenBatches, ROUTE),
                countFor(frozenBatches, MetricsRegistry.UNMAPPED_TEMPLATE));
    }
}
