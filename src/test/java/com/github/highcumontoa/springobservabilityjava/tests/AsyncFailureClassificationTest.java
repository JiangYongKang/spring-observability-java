package com.github.highcumontoa.springobservabilityjava.tests;

import java.util.List;
import java.util.concurrent.TimeUnit;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.springobservabilityjava.observability.correlation.CorrelationIdFilter;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.ErrorClass;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.MetricsRegistry;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.RouteMetrics;
import com.github.highcumontoa.springobservabilityjava.observability.web.AsyncWorkService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 异步失败归因端到端测试：
 * <ul>
 *   <li>异步任务失败时，工作线程日志按同一套分类记录，且日志上带同一关联标识；</li>
 *   <li>请求线程等到结果时，响应状态/错误类别与日志分类一致，失败如实计入指标（5xx 桶），
 *       与成功请求可区分；</li>
 *   <li>内部异常细节（伪造凭据）只出现在服务端日志，绝不进入响应体；</li>
 *   <li>成功路径与失败路径计数互不串。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AsyncFailureClassificationTest {

    /** 必须与 {@code AsyncWorkService} 失败分支抛出的内部消息中的敏感片段保持一致。 */
    private static final String ASYNC_SECRET = "async-secret-9f8e7d";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MetricsRegistry metricsRegistry;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger asyncLogger;

    @BeforeEach
    void attachLogAppender() {
        asyncLogger = (Logger) LoggerFactory.getLogger(AsyncWorkService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        asyncLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        asyncLogger.detachAppender(logAppender);
    }

    private long countOf(String route, ErrorClass errorClass) {
        return metricsRegistry.snapshot().stream()
                .filter(m -> m.getRouteTemplate().equals(route))
                .findFirst()
                .map(m -> m.getCount(errorClass))
                .orElse(0L);
    }

    @Test
    void asyncFailureIsClassifiedConsistentlyInLogMetricsAndResponseAndMasked() throws Exception {
        String corrId = "async-fail-001";
        long serverBefore = countOf("/api/items/{itemId}/checkout", ErrorClass.SERVER_ERROR);
        long successBefore = countOf("/api/items/{itemId}/checkout", ErrorClass.NONE);

        MvcResult result = mockMvc.perform(post("/api/items/{itemId}/checkout", "777")
                        .param("fail", "true")
                        .header(CorrelationIdFilter.HEADER_NAME, corrId))
                .andExpect(status().isInternalServerError())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        JsonNode body = JSON.readTree(content);
        // 响应：关联标识 + 归一类别 + 固定脱敏文案，三处同源
        assertEquals(corrId, body.get("correlationId").asText());
        assertEquals("SERVER_OTHER", body.get("errorCategory").asText());
        assertEquals("internal server error", body.get("reason").asText());

        // 脱敏必须是"生效的"：敏感串若没被挡住，必然原样出现在响应体里
        assertFalse(content.contains(ASYNC_SECRET),
                "internal async secret must never reach the caller");
        assertFalse(content.contains("dbToken"),
                "internal exception message must never reach the caller");

        // 指标：失败如实计入 SERVER_ERROR，而不是成功
        assertEquals(serverBefore + 1,
                countOf("/api/items/{itemId}/checkout", ErrorClass.SERVER_ERROR),
                "async failure must be counted as server error");
        assertEquals(successBefore,
                countOf("/api/items/{itemId}/checkout", ErrorClass.NONE),
                "async failure must not be counted as success");

        // 工作线程日志：同分类 + 同关联标识 + 敏感串确实留在了服务端日志（证明脱敏断言有效）
        List<ILoggingEvent> failureLogs = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .filter(e -> e.getFormattedMessage().contains("async job failed"))
                .filter(e -> e.getFormattedMessage().contains("SERVER_OTHER"))
                .toList();
        assertEquals(1, failureLogs.size(), "exactly one classified failure log on worker thread");
        ILoggingEvent failureLog = failureLogs.get(0);
        assertEquals(corrId, failureLog.getMDCPropertyMap().get("correlationId"),
                "worker thread must carry the same correlation id");
        boolean loggedWithSecret = failureLog.getThrowableProxy() != null
                && failureLog.getThrowableProxy().getMessage() != null
                && failureLog.getThrowableProxy().getMessage().contains(ASYNC_SECRET);
        assertTrue(loggedWithSecret,
                "full internal detail must be preserved in the server-side error log");
    }

    @Test
    void asyncSuccessIsCountedAsSuccessAndCarriesSameCorrelationId() throws Exception {
        String corrId = "async-ok-001";
        long successBefore = countOf("/api/items/{itemId}/checkout", ErrorClass.NONE);
        long serverBefore = countOf("/api/items/{itemId}/checkout", ErrorClass.SERVER_ERROR);

        MvcResult result = mockMvc.perform(post("/api/items/{itemId}/checkout", "888")
                        .header(CorrelationIdFilter.HEADER_NAME, corrId))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertEquals("job-for-888-" + corrId, body.get("asyncJob").asText());

        assertEquals(successBefore + 1,
                countOf("/api/items/{itemId}/checkout", ErrorClass.NONE));
        assertEquals(serverBefore,
                countOf("/api/items/{itemId}/checkout", ErrorClass.SERVER_ERROR));

        boolean workerSawCorrelation = logAppender.list.stream()
                .anyMatch(e -> e.getFormattedMessage().contains("async processing item")
                        && corrId.equals(e.getMDCPropertyMap().get("correlationId")));
        assertTrue(workerSawCorrelation, "success log on worker must carry same correlation id");
        boolean noFailureLog = logAppender.list.stream()
                .noneMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("async job failed"));
        assertTrue(noFailureLog, "success path must not produce a failure log");
    }

    @Test
    void failedAndSucceededRequestsAreDistinguishableInAggregateView() {
        RouteMetrics route = metricsRegistry.metricsFor("/api/items/{itemId}/checkout");
        long success = route.getCount(ErrorClass.NONE);
        long serverError = route.getCount(ErrorClass.SERVER_ERROR);
        // 本类前面的用例至少制造了 1 次成功与 1 次失败，聚合视图两者都可见且互不相等地累计
        assertTrue(success >= 1L && serverError >= 1L,
                "aggregate must expose both success and failure counters separately");
    }
}
