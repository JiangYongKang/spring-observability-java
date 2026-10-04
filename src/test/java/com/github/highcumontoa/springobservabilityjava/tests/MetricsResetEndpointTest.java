package com.github.highcumontoa.springobservabilityjava.tests;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.springobservabilityjava.observability.correlation.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.highcumontoa.springobservabilityjava.observability.metrics.MetricsRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 重置端点端到端测试：
 * <ul>
 *   <li>重置冻结上一批、当前批次从 0 开始；重置后新请求只进新批次；</li>
 *   <li>查看/重置统计这两个操作入口自身不计入任何批次；</li>
 *   <li>重置那一刻仍在途的请求不进入新批次（按进入时刻定归属）。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class MetricsResetEndpointTest {

    private static final Logger log = LoggerFactory.getLogger(MetricsResetEndpointTest.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 门控端点：进入控制器时放行 entered 闩，阻塞在 release 闩上，用于确定性地制造"在途请求"。 */
    @RestController
    static class GatedController {

        static volatile CountDownLatch entered = new CountDownLatch(1);
        static volatile CountDownLatch release = new CountDownLatch(1);

        static void resetLatches() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        @GetMapping("/api/test-gated")
        Map<String, Object> gated() throws InterruptedException {
            entered.countDown();
            assertTrue(release.await(30, TimeUnit.SECONDS), "test gate must be released");
            return Map.of("gated", true);
        }
    }

    @TestConfiguration
    static class GatedEndpointConfig {
        @Bean
        GatedController gatedController() {
            return new GatedController();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MetricsRegistry metricsRegistry;

    private long routeCountInView(JsonNode view, String route) {
        for (JsonNode routeNode : view.get("routes")) {
            if (routeNode.get("route").asText().equals(route)) {
                return routeNode.get("count").asLong();
            }
        }
        return 0L;
    }

    private long totalCountInView(JsonNode view) {
        long total = 0L;
        for (JsonNode routeNode : view.get("routes")) {
            total += routeNode.get("count").asLong();
        }
        return total;
    }

    @Test
    void resetEndpointFreezesHistoryAndNewBatchStartsAtZero() throws Exception {
        // 每个用例独立上下文，先重置到干净基线
        mockMvc.perform(post("/internal/metrics/reset")).andExpect(status().isOk());

        // 制造 2 个已知请求（同一路由、不同参数）
        mockMvc.perform(get("/api/items/1").header(CorrelationIdFilter.HEADER_NAME, "reset-a"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/items/2").header(CorrelationIdFilter.HEADER_NAME, "reset-b"))
                .andExpect(status().isOk());

        MvcResult resetResult = mockMvc.perform(post("/internal/metrics/reset"))
                .andExpect(status().isOk()).andReturn();
        JsonNode frozen = JSON.readTree(resetResult.getResponse().getContentAsString());
        assertEquals(2L, routeCountInView(frozen, "/api/items/{itemId}"),
                "frozen batch must contain pre-reset requests aggregated by route template");
        long frozenGeneration = frozen.get("generation").asLong();
        assertEquals(frozenGeneration + 1, frozen.get("nextGeneration").asLong());

        // 当前视图：新批次，序号 +1，计数为 0
        MvcResult metricsResult = mockMvc.perform(get("/internal/metrics"))
                .andExpect(status().isOk()).andReturn();
        JsonNode current = JSON.readTree(metricsResult.getResponse().getContentAsString());
        assertEquals(frozenGeneration + 1, current.get("generation").asLong());
        assertEquals(0L, routeCountInView(current, "/api/items/{itemId}"),
                "post-reset batch must start at zero");

        // 重置后的请求只进新批次，不回灌历史
        mockMvc.perform(get("/api/items/3").header(CorrelationIdFilter.HEADER_NAME, "reset-c"))
                .andExpect(status().isOk());
        MvcResult metricsResult2 = mockMvc.perform(get("/internal/metrics")).andReturn();
        JsonNode current2 = JSON.readTree(metricsResult2.getResponse().getContentAsString());
        assertEquals(1L, routeCountInView(current2, "/api/items/{itemId}"),
                "post-reset requests must accumulate only in the new batch");
        assertTrue(current2.get("generation").asLong() > frozenGeneration);

        log.info("reset endpoint check: frozenCount=2, nextGeneration={}, postResetCount=1",
                current2.get("generation").asLong());
    }

    @Test
    void opsEndpointsAreNotCountedInAnyBatch() throws Exception {
        // 重置到干净基线；这次重置自身也不应出现在任何批次里
        MvcResult baselineReset = mockMvc.perform(post("/internal/metrics/reset"))
                .andExpect(status().isOk()).andReturn();
        JsonNode baselineFrozen = JSON.readTree(baselineReset.getResponse().getContentAsString());
        for (JsonNode routeNode : baselineFrozen.get("routes")) {
            assertTrue(!routeNode.get("route").asText().startsWith("/internal/"),
                    "ops endpoints must never appear in frozen batches: " + routeNode.get("route"));
        }

        // 重置后立刻查看：新批次必须完全为空——查看与重置这两个操作自身不留任何记录
        MvcResult metricsResult = mockMvc.perform(get("/internal/metrics"))
                .andExpect(status().isOk()).andReturn();
        JsonNode current = JSON.readTree(metricsResult.getResponse().getContentAsString());
        assertEquals(0L, totalCountInView(current),
                "right after reset, the new batch must not contain the reset/metrics calls themselves");
        assertEquals(0L, routeCountInView(current, MetricsRegistry.UNMAPPED_TEMPLATE));

        // 再重置一次：冻结的上一批里同样不能混入刚才那次查看统计的请求
        MvcResult secondReset = mockMvc.perform(post("/internal/metrics/reset"))
                .andExpect(status().isOk()).andReturn();
        JsonNode frozenAgain = JSON.readTree(secondReset.getResponse().getContentAsString());
        assertEquals(0L, totalCountInView(frozenAgain),
                "frozen batch must not contain the metrics-view request either");

        // 真正的业务请求进入新批次，且只有它被统计
        mockMvc.perform(get("/api/items/9").header(CorrelationIdFilter.HEADER_NAME, "ops-exclusion"))
                .andExpect(status().isOk());
        MvcResult metricsResult2 = mockMvc.perform(get("/internal/metrics"))
                .andExpect(status().isOk()).andReturn();
        JsonNode current2 = JSON.readTree(metricsResult2.getResponse().getContentAsString());
        assertEquals(1L, routeCountInView(current2, "/api/items/{itemId}"));
        assertEquals(1L, totalCountInView(current2),
                "only the real business request is counted; the metrics view itself is not");

        log.info("ops exclusion check: postResetTotal=0, frozenAgainTotal=0, businessOnlyCount=1");
    }

    @Test
    void inFlightRequestAtResetStaysOutOfNewBatch() throws Exception {
        GatedController.resetLatches();
        mockMvc.perform(post("/internal/metrics/reset")).andExpect(status().isOk());

        // 后台发起一个会阻塞在门控上的请求；闩放行即说明它已"进入"（过滤器已钉好归属批次）
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<MvcResult> inFlight = executor.submit(() -> mockMvc.perform(
                        get("/api/test-gated").header(CorrelationIdFilter.HEADER_NAME, "in-flight-1"))
                .andExpect(status().isOk()).andReturn());
        assertTrue(GatedController.entered.await(10, TimeUnit.SECONDS),
                "gated request should have entered before reset");

        // 请求仍在途时重置：边界必须切干净——它归属重置前的旧批次
        MvcResult resetResult = mockMvc.perform(post("/internal/metrics/reset"))
                .andExpect(status().isOk()).andReturn();
        JsonNode frozen = JSON.readTree(resetResult.getResponse().getContentAsString());
        long nextGeneration = frozen.get("nextGeneration").asLong();

        // 放行，让在途请求在重置之后才结束
        GatedController.release.countDown();
        MvcResult completed = inFlight.get(30, TimeUnit.SECONDS);
        executor.shutdown();
        assertEquals(200, completed.getResponse().getStatus());

        // 新批次：在途请求虽已结束，但绝不能混入；必须仍然完全为空
        MvcResult metricsResult = mockMvc.perform(get("/internal/metrics"))
                .andExpect(status().isOk()).andReturn();
        JsonNode current = JSON.readTree(metricsResult.getResponse().getContentAsString());
        assertEquals(nextGeneration, current.get("generation").asLong());
        assertEquals(0L, routeCountInView(current, "/api/test-gated"),
                "request that entered before reset must not appear in the new batch");
        assertEquals(0L, totalCountInView(current),
                "new batch must only accumulate requests that entered after reset");

        // 重置之后才进入的请求正常计入新批次，与在途请求互不干扰
        mockMvc.perform(get("/api/items/7").header(CorrelationIdFilter.HEADER_NAME, "post-reset"))
                .andExpect(status().isOk());
        MvcResult metricsResult2 = mockMvc.perform(get("/internal/metrics"))
                .andExpect(status().isOk()).andReturn();
        JsonNode current2 = JSON.readTree(metricsResult2.getResponse().getContentAsString());
        assertEquals(1L, routeCountInView(current2, "/api/items/{itemId}"));
        assertEquals(0L, routeCountInView(current2, "/api/test-gated"));

        log.info("in-flight endpoint check: generation={}, inFlightInNewBatch=0, postResetBusinessCount=1",
                current2.get("generation").asLong());
    }
}
