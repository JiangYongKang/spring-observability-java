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

    @Test
    void wrappedClientErrorIsConsistentAcrossResponseAndMetrics() throws Exception {
        String corrId = "err-client-001";
        long clientBefore = countOf("/api/oops", ErrorClass.CLIENT_ERROR);
        long serverBefore = countOf("/api/oops", ErrorClass.SERVER_ERROR);
        MvcResult result = mockMvc.perform(get("/api/oops").param("kind", "client")
                        .header(CorrelationIdFilter.HEADER_NAME, corrId))
                .andExpect(status().isNotFound())
                .andReturn();

        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertEquals(corrId, body.get("correlationId").asText());
        assertEquals("NOT_FOUND", body.get("errorCategory").asText());
        assertEquals("resource not found", body.get("reason").asText());
        // 内部异常消息绝不外泄
        assertFalse(result.getResponse().getContentAsString().contains("secret resource id 42"));

        assertEquals(clientBefore + 1, countOf("/api/oops", ErrorClass.CLIENT_ERROR));
        assertEquals(serverBefore, countOf("/api/oops", ErrorClass.SERVER_ERROR));

        log.info("client-error consistency check: route=/api/oops, correlationId={}, errorClass=CLIENT_ERROR, errorCategory=NOT_FOUND",
                corrId);
    }

    @Test
    void serverErrorIsMaskedAndRecordedAsServerError() throws Exception {
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
        assertFalse(content.contains("hunter2"), "internal secret must not leak");
        assertFalse(content.contains("password"), "internal message must not leak");

        assertEquals(serverBefore + 1, countOf("/api/oops", ErrorClass.SERVER_ERROR));

        log.info("server-error consistency check: route=/api/oops, correlationId={}, errorClass=SERVER_ERROR, errorCategory=SERVER_OTHER",
                corrId);
    }

    @Test
    void unknownPathIsRecordedInUnmappedBucketOnly() throws Exception {
        long unmappedBefore = countOf(MetricsRegistry.UNMAPPED_TEMPLATE, ErrorClass.CLIENT_ERROR);
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

        log.info("unmapped cardinality check: route=UNMAPPED, errorClass=CLIENT_ERROR, addedCount=2");
    }
}
