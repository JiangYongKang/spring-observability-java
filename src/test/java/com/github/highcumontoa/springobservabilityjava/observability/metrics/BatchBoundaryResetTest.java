package com.github.highcumontoa.springobservabilityjava.observability.metrics;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统计重置的批次归属边界单测（包内可见，直接驱动 {@link MetricsRegistry#openBatch()} 生命周期）。
 *
 * <p>覆盖的业务场景（对应压测/回归时关心的批次边界）：</p>
 * <ul>
 *   <li>业务路由 {@code GET /api/items/{itemId}}：重置前进入、重置后才结束的在途请求；</li>
 *   <li>重置之后才进入的新请求；</li>
 *   <li>无法归属路由的 {@code UNMAPPED} 兜底请求同样遵守同一归属边界。</li>
 * </ul>
 */
class BatchBoundaryResetTest {

    private static final Logger log = LoggerFactory.getLogger(BatchBoundaryResetTest.class);
    private static final String ROUTE = "GET /api/items/{itemId}";

    private static long routeCount(RegistrySnapshot snapshot, String route) {
        return snapshot.routes().stream()
                .filter(r -> r.route().equals(route))
                .mapToLong(RouteSnapshot::count)
                .findFirst().orElse(0L);
    }

    @Test
    void requestEnteredBeforeResetIsFrozenInOldBatchEvenIfItFinishesAfter() throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        assertEquals(0L, registry.currentGenerationId());

        // 业务请求在重置之前已经进入（领取了第 0 代票据），但此刻尚未结束
        RequestBatch inFlight = registry.openBatch();
        assertEquals(0L, inFlight.generationId());

        // 重置：会排空（等待）这个在途请求——在另一个线程里发起重置，主线程随后结束该请求。
        final RegistrySnapshot[] frozenHolder = new RegistrySnapshot[1];
        final Thread resetter = new Thread(() -> frozenHolder[0] = registry.reset(), "resetter");
        resetter.start();

        // 确定性等待：重置线程应已进入排空等待（在途请求未结束前不得切换代）。
        // 注意：重置期间持有注册表写锁，这里不能调用任何需要读锁的注册表方法，否则会自我阻塞。
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        Thread.State state;
        while ((state = resetter.getState()) != Thread.State.TIMED_WAITING
                && state != Thread.State.WAITING
                && resetter.isAlive()
                && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(resetter.isAlive()
                        && (resetter.getState() == Thread.State.TIMED_WAITING
                        || resetter.getState() == Thread.State.WAITING),
                "reset must remain blocked on draining the in-flight request before it finishes");

        // 在途请求在"重置之后"才结束：它必须记回进入时的第 0 代
        inFlight.finish(ROUTE, 5_000L, ErrorClass.NONE);

        resetter.join(5_000L);
        assertEquals(Thread.State.TERMINATED, resetter.getState(),
                "reset must return once the in-flight request finishes");

        RegistrySnapshot frozen = frozenHolder[0];
        assertEquals(0L, frozen.generation());
        assertEquals(1L, routeCount(frozen, ROUTE),
                "request entered before reset must be frozen in the old batch even when it finishes after reset");

        // 重置之后：新一批必须从零开始，不包含任何重置前进入的请求
        assertEquals(1L, registry.currentGenerationId());
        assertEquals(0L, registry.metricsFor(ROUTE).getCount(),
                "new batch must not contain any request that entered before reset");

        log.info("in-flight boundary: enteredGen=0, finishedAfterReset -> frozen gen0 count=1, current gen={} count=0",
                registry.currentGenerationId());
    }

    @Test
    void requestsEnteredAfterResetAccumulateOnlyInNewBatchIncludingUnmapped() {
        MetricsRegistry registry = new MetricsRegistry();

        // 重置前有一笔业务请求与一笔兜底请求（都在重置前进入并结束）
        registry.openBatch().finish(ROUTE, 1_000L, ErrorClass.NONE);
        registry.openBatch().finish(MetricsRegistry.UNMAPPED_TEMPLATE, 2_000L, ErrorClass.CLIENT_ERROR);

        RegistrySnapshot frozen = registry.reset();
        assertEquals(0L, frozen.generation());
        assertEquals(1L, routeCount(frozen, ROUTE));
        assertEquals(1L, routeCount(frozen, MetricsRegistry.UNMAPPED_TEMPLATE));
        assertEquals(1L, registry.currentGenerationId());

        // 重置之后才进入的请求：业务路由 2 笔、兜底 1 笔，只能累计在新批次
        registry.openBatch().finish(ROUTE, 3_000L, ErrorClass.NONE);
        registry.openBatch().finish(ROUTE, 4_000L, ErrorClass.SERVER_ERROR);
        registry.openBatch().finish(MetricsRegistry.UNMAPPED_TEMPLATE, 5_000L, ErrorClass.CLIENT_ERROR);

        assertEquals(2L, registry.metricsFor(ROUTE).getCount());
        assertEquals(1L, registry.metricsFor(ROUTE).getCount(ErrorClass.SERVER_ERROR));
        assertEquals(1L, registry.metricsFor(MetricsRegistry.UNMAPPED_TEMPLATE).getCount());

        // 再重置：冻结的是第 1 批（业务 2 + 兜底 1），与第 0 批互不污染
        RegistrySnapshot frozen1 = registry.reset();
        assertEquals(1L, frozen1.generation());
        assertEquals(2L, routeCount(frozen1, ROUTE));
        assertEquals(1L, routeCount(frozen1, MetricsRegistry.UNMAPPED_TEMPLATE));
        assertEquals(1L, routeCount(frozen, ROUTE), "older frozen batch must remain unchanged");
        assertEquals(2L, registry.currentGenerationId());
        assertEquals(0L, registry.metricsFor(ROUTE).getCount());

        log.info("post-reset accumulation: gen0(business=1,unmapped=1) frozen; "
                        + "gen1(business=2,unmapped=1) frozen; current gen={} starts at zero",
                registry.currentGenerationId());
    }
}
