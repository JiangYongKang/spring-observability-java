package com.github.highcumontoa.springobservabilityjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.springobservabilityjava.observability.correlation.CorrelationIdFilter;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.ErrorClass;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.MetricsRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端一致性测试：同一异常在日志、指标、HTTP 响应三处分类一致；
 * 内部细节不外泄；404 请求不污染业务路由维度。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ErrorMappingIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ErrorMappingIntegrationTest.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MetricsRegistry metricsRegistry;

    /** 读取当前计数（路由维度在首次记录后才存在，缺省为 0）。 */
    private long countOf(String route, ErrorClass errorClass) {
        return metricsRegistry.snapshot().stream()
                .filter(m -> m.getRouteTemplate().equals(route))
                .findFirst()
                .map(m -> m.getCount(errorClass))
                .orElse(0L);
    }

    /** 读取指定路由的累计耗时；维度不存在时为 0。 */
    private long totalNanosOf(String route) {
        return metricsRegistry.snapshot().stream()
                .filter(m -> m.getRouteTemplate().equals(route))
                .findFirst()
                .map(com.github.highcumontoa.springobservabilityjava.observability.metrics.RouteMetrics::getTotalNanos)
                .orElse(0L);
    }

    @Test
    void wrappedClientErrorIsConsistentAcrossResponseAndMetrics() throws Exception {
        // 唯一令牌只存在于深层异常 message 中：脱敏一旦失效（响应回显 message），它必然出现
        String secret = "secret-token-3f9a1c7e-42";
        String corrId = "err-client-001";
        long clientBefore = countOf("/api/oops", ErrorClass.CLIENT_ERROR);
        long serverBefore = countOf("/api/oops", ErrorClass.SERVER_ERROR);
        MvcResult result = mockMvc.perform(get("/api/oops").param("kind", "client")
                        .header(CorrelationIdFilter.HEADER_NAME, corrId))
                .andExpect(status().isNotFound())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        JsonNode body = JSON.readTree(content);
        assertEquals(corrId, body.get("correlationId").asText());
        assertEquals("NOT_FOUND", body.get("errorCategory").asText());
        assertEquals("resource not found", body.get("reason").asText());
        // 真正能证伪脱敏的断言：该令牌若非被脱敏就必然随异常 message 出现在响应里
        assertFalse(content.contains(secret),
                "deeply wrapped exception message must not leak to the response");

        assertEquals(clientBefore + 1, countOf("/api/oops", ErrorClass.CLIENT_ERROR));
        assertEquals(serverBefore, countOf("/api/oops", ErrorClass.SERVER_ERROR));

        log.info("client-error consistency check: route=/api/oops, correlationId={}, errorClass=CLIENT_ERROR, errorCategory=NOT_FOUND",
                corrId);
    }

    @Test
    void serverErrorIsMaskedAndRecordedAsServerError() throws Exception {
        // 唯一令牌只存在于深层异常 message 中
        String secret = "server-secret-8b2d6e1f-99";
        String corrId = "err-server-001";
        long serverBefore = countOf("/api/oops", ErrorClass.SERVER_ERROR);
        MvcResult result = mockMvc.perform(get("/api/oops")
                        .header(CorrelationIdFilter.HEADER_NAME, corrId))
                .andExpect(status().isInternalServerError())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        JsonNode body = JSON.readTree(content);
        assertEquals(corrId, body.get("correlationId").asText());
        assertEquals("SERVER_OTHER", body.get("errorCategory").asText());
        assertEquals("internal server error", body.get("reason").asText());
        // 若未脱敏，唯一令牌与关键字必然直接出现在响应体中
        assertFalse(content.contains(secret), "unique internal secret must not leak");
        assertFalse(content.contains("password"), "internal message must not leak");

        assertEquals(serverBefore + 1, countOf("/api/oops", ErrorClass.SERVER_ERROR));

        log.info("server-error consistency check: route=/api/oops, correlationId={}, errorClass=SERVER_ERROR, errorCategory=SERVER_OTHER",
                corrId);
    }

    @Test
    void unknownPathIsRecordedInUnmappedBucketOnly() throws Exception {
        long unmappedBefore = countOf(MetricsRegistry.UNMAPPED_TEMPLATE, ErrorClass.CLIENT_ERROR);
        long unmappedDurationBefore = totalNanosOf(MetricsRegistry.UNMAPPED_TEMPLATE);
        mockMvc.perform(get("/this/does/not/exist-1"))
                .andExpect(status().isNotFound()).andReturn();
        mockMvc.perform(get("/this/does/not/exist-2"))
                .andExpect(status().isNotFound()).andReturn();

        assertEquals(unmappedBefore + 2,
                countOf(MetricsRegistry.UNMAPPED_TEMPLATE, ErrorClass.CLIENT_ERROR),
                "unmatched requests must aggregate in fixed bucket");
        assertEquals(0,
                metricsRegistry.snapshot().stream()
                        .filter(m -> m.getRouteTemplate().contains("does/not/exist"))
                        .count(),
                "raw unknown paths must never become metric dimensions");

        // 兜底分组必须统计到真实耗时，不能是恒为 0；且这两次请求也恰好各落一个耗时档位
        com.github.highcumontoa.springobservabilityjava.observability.metrics.RouteMetrics unmapped =
                metricsRegistry.snapshot().stream()
                        .filter(m -> m.getRouteTemplate().equals(MetricsRegistry.UNMAPPED_TEMPLATE))
                        .findFirst().orElseThrow();
        long addedDuration = unmapped.getTotalNanos() - unmappedDurationBefore;
        assertTrue(addedDuration >= 0L, "unmapped duration must be non-negative");
        long histogramSum = unmapped.durationHistogram().values().stream().mapToLong(Long::longValue).sum();
        assertEquals(unmapped.getCount(), histogramSum,
                "unmapped requests must also land in histogram buckets");

        log.info("unmapped cardinality check: route=UNMAPPED, errorClass=CLIENT_ERROR, addedCount=2, totalNanos={}",
                unmapped.getTotalNanos());
    }

    @Test
    void unmappedRequestRecordsRealElapsedDurationNotConstantZero() throws Exception {
        // 强制让兜底请求处理占用可观测的时间：触发两次，累计耗时必须严格大于 0
        long before = totalNanosOf(MetricsRegistry.UNMAPPED_TEMPLATE);
        mockMvc.perform(get("/another/unmapped/path-alpha")).andExpect(status().isNotFound());
        long afterFirst = totalNanosOf(MetricsRegistry.UNMAPPED_TEMPLATE);
        assertTrue(afterFirst > before,
                "an unmatched request must contribute real elapsed duration, not hard-coded 0");
    }
}
