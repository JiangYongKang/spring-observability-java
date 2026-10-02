package com.github.highcumontoa.springobservabilityjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.springobservabilityjava.observability.correlation.CorrelationIdFilter;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.MetricsRegistry;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.RouteMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统计重置的 HTTP 端到端测试：{@code POST /internal/metrics/reset} 之后快照从零开始，
 * 重置前的请求不进入新批次；重置端点自身的访问也按其路由模板计入新批次。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MetricsResetIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MetricsRegistry metricsRegistry;

    private long routeCount(String route) {
        return metricsRegistry.snapshot().stream()
                .filter(m -> m.getRouteTemplate().equals(route))
                .findFirst()
                .map(RouteMetrics::getCount)
                .orElse(0L);
    }

    @Test
    void resetEndpointStartsFreshBatchAndHistoryStaysOut() throws Exception {
        // 先制造一批历史请求
        mockMvc.perform(get("/api/items/1").header(CorrelationIdFilter.HEADER_NAME, "reset-history-1"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/items/2").header(CorrelationIdFilter.HEADER_NAME, "reset-history-2"))
                .andExpect(status().isOk());
        long historyCount = routeCount("/api/items/{itemId}");
        assertTrue(historyCount >= 2L);

        // 重置到明确时刻
        MvcResult resetResult = mockMvc.perform(post("/internal/metrics/reset"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode resetBody = JSON.readTree(resetResult.getResponse().getContentAsString());
        long newGeneration = resetBody.get("generation").asLong();
        assertTrue(newGeneration >= 1L);
        assertTrue(resetBody.has("startedAt"));

        // 重置后业务路由从零开始
        assertEquals(0L, routeCount("/api/items/{itemId}"),
                "pre-reset history must not appear in the fresh batch");

        // 新批次里再发请求，独立累计
        mockMvc.perform(get("/api/items/3").header(CorrelationIdFilter.HEADER_NAME, "reset-fresh-1"))
                .andExpect(status().isOk());
        assertEquals(1L, routeCount("/api/items/{itemId}"));

        // 指标快照中能看到固定档位分布，且各档之和与总数自洽
        MvcResult metricsResult = mockMvc.perform(get("/internal/metrics")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode routes = JSON.readTree(metricsResult.getResponse().getContentAsString());
        boolean found = false;
        for (JsonNode route : routes) {
            if ("/api/items/{itemId}".equals(route.get("route").asText())) {
                found = true;
                assertEquals(1L, route.get("count").asLong());
                JsonNode buckets = route.get("latencyBuckets");
                long bucketSum = 0L;
                var fields = buckets.fieldNames();
                while (fields.hasNext()) {
                    bucketSum += buckets.get(fields.next()).get("count").asLong();
                }
                assertEquals(1L, bucketSum, "bucket counts must sum to route count");
            }
        }
        assertTrue(found, "fresh batch must contain the route after one request");
    }
}
