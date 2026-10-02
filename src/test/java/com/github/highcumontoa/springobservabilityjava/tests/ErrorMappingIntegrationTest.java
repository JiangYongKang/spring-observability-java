package com.github.highcumontoa.springobservabilityjava.tests;

import java.util.List;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.springobservabilityjava.observability.correlation.CorrelationIdFilter;
import com.github.highcumontoa.springobservabilityjava.observability.error.GlobalExceptionHandler;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.ErrorClass;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.MetricsRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * 内部细节不外泄（且通过"敏感串确实出现在服务端异常中"来证明脱敏断言有效）；
 * 404 请求不污染业务路由维度。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ErrorMappingIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MetricsRegistry metricsRegistry;

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logger.detachAppender(logAppender);
    }

    /** 读取当前计数（路由维度在首次记录后才存在，缺省为 0）。 */
    private long countOf(String route, ErrorClass errorClass) {
        return metricsRegistry.snapshot().stream()
                .filter(m -> m.getRouteTemplate().equals(route))
                .findFirst()
                .map(m -> m.getCount(errorClass))
                .orElse(0L);
    }

    /** 服务端日志中是否记录了包含指定文本的异常消息（用于证明"脱敏前信息确实存在"）。 */
    private boolean serverLogContains(String text) {
        return logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR || e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getThrowableProxy)
                .filter(java.util.Objects::nonNull)
                .anyMatch(throwable -> containsInChain(throwable, text));
    }

    private boolean containsInChain(ch.qos.logback.classic.spi.IThrowableProxy throwable, String text) {
        for (ch.qos.logback.classic.spi.IThrowableProxy current = throwable;
             current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void wrappedClientErrorIsConsistentAcrossResponseAndMetrics() throws Exception {
        String corrId = "err-client-001";
        // 控制器实际抛出的 404 细类消息文本（ResponseStatusException reason）
        String internalDetail = "missing resource 42";
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

        // 脱敏有效性证明：内部细类消息确实进入了服务端异常链；若脱敏失效它必然出现在响应里
        assertTrue(serverLogContains(internalDetail),
                "the sensitive internal detail must actually be thrown/logged before masking");
        assertFalse(result.getResponse().getContentAsString().contains(internalDetail),
                "internal detail must not reach the caller");

        assertEquals(clientBefore + 1, countOf("/api/oops", ErrorClass.CLIENT_ERROR));
        assertEquals(serverBefore, countOf("/api/oops", ErrorClass.SERVER_ERROR));
    }

    @Test
    void serverErrorIsMaskedAndRecordedAsServerError() throws Exception {
        String corrId = "err-server-001";
        // 与 DemoController 默认分支抛出的内部消息保持一致
        String secretToken = "hunter2";
        String secretField = "password";
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

        // 脱敏有效性证明：敏感串确实存在于服务端完整异常日志中（没被挡住就会进响应体）
        assertTrue(serverLogContains(secretToken),
                "secret must genuinely appear in the internal exception before masking");
        assertTrue(serverLogContains(secretField));
        assertFalse(content.contains(secretToken), "internal secret must not leak");
        assertFalse(content.contains(secretField), "internal message must not leak");

        assertEquals(serverBefore + 1, countOf("/api/oops", ErrorClass.SERVER_ERROR));
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

        // 未匹配路由的请求现在也有真实耗时，不再恒为 0
        List<com.github.highcumontoa.springobservabilityjava.observability.metrics.RouteMetrics>
                snapshot = metricsRegistry.snapshot();
        long unmappedTotalNanos = snapshot.stream()
                .filter(m -> m.getRouteTemplate().equals(MetricsRegistry.UNMAPPED_TEMPLATE))
                .findFirst()
                .map(com.github.highcumontoa.springobservabilityjava.observability.metrics.RouteMetrics::getTotalNanos)
                .orElseThrow();
        assertTrue(unmappedTotalNanos > 0L,
                "unmatched requests must be measured with real durations, not always zero");
    }
}
