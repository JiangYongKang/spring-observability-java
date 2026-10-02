package com.github.highcumontoa.springobservabilityjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.springobservabilityjava.observability.correlation.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.github.highcumontoa.springobservabilityjava.observability.metrics.MetricsRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 重置端点端到端测试：重置冻结上一批、当前批次从 0 开始；重置后新请求只进新批次。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MetricsResetEndpointTest {

    private static final Logger log = LoggerFactory.getLogger(MetricsResetEndpointTest.class);
    private static final ObjectMapper JSON = new ObjectMapper();

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
}
