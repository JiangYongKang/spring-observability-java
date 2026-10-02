package com.github.highcumontoa.springobservabilityjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.springobservabilityjava.observability.correlation.CorrelationIdFilter;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.ErrorClass;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.MetricsRegistry;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.RouteMetrics;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异步失败归因集成测试：
 * <ul>
 *   <li>异步任务的深层包装异常在等待结果的请求上被正确归因（404/500），
 *       响应、日志、指标三处同一分类；</li>
 *   <li>异步失败计入失败计数，与成功请求明确区分，不被算成成功；</li>
 *   <li>响应仍携带同一个关联标识（工作线程透传），内部异常细节不外泄。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AsyncFailureAttributionTest {

    private static final Logger log = LoggerFactory.getLogger(AsyncFailureAttributionTest.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROUTE = "/api/items/{itemId}/checkout";

    @LocalServerPort
    private int port;

    @Autowired
    private MetricsRegistry metricsRegistry;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders headers(String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(CorrelationIdFilter.HEADER_NAME, correlationId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void asyncClientFailureIsClassifiedConsistentlyAndNotCountedAsSuccess() throws Exception {
        String corrId = "async-fail-client-001";
        RouteMetrics before = metricsRegistry.metricsFor(ROUTE);
        long totalBefore = before.getCount();
        long noneBefore = before.getCount(ErrorClass.NONE);
        long clientBefore = before.getCount(ErrorClass.CLIENT_ERROR);

        HttpClientErrorException ex = assertThrows(HttpClientErrorException.class, () -> {
            RestTemplate restTemplate = new RestTemplate();
            restTemplate.postForEntity(url("/api/items/200/checkout?asyncFail=client"),
                    new HttpEntity<>("{}", headers(corrId)), String.class);
        });

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals(corrId, ex.getResponseHeaders().getFirst(CorrelationIdFilter.HEADER_NAME),
                "async failure response must carry the same correlation id");
        JsonNode body = JSON.readTree(ex.getResponseBodyAsString());
        assertEquals(corrId, body.get("correlationId").asText());
        assertEquals("NOT_FOUND", body.get("errorCategory").asText());
        assertEquals("resource not found", body.get("reason").asText());

        RouteMetrics after = metricsRegistry.metricsFor(ROUTE);
        assertEquals(noneBefore, after.getCount(ErrorClass.NONE),
                "a failed async job must not be counted as success");
        assertEquals(clientBefore + 1, after.getCount(ErrorClass.CLIENT_ERROR),
                "async client failure must be counted as CLIENT_ERROR");
        assertEquals(totalBefore + 1, after.getCount(), "total count increases by exactly one");

        log.info("async client-failure check: route={}, correlationId={}, errorClass=CLIENT_ERROR",
                ROUTE, corrId);
    }

    @Test
    void asyncServerFailureIsMaskedClassifiedAndKeepsCorrelationId() throws Exception {
        // 唯一敏感串：若脱敏失效（如处理器回显异常 message），它必然出现在响应体中
        String secret = "async-secret-7f3a9c";
        String corrId = "async-fail-server-001";
        RouteMetrics before = metricsRegistry.metricsFor(ROUTE);
        long totalBefore = before.getCount();
        long noneBefore = before.getCount(ErrorClass.NONE);
        long serverBefore = before.getCount(ErrorClass.SERVER_ERROR);

        HttpServerErrorException ex = assertThrows(HttpServerErrorException.class, () -> {
            RestTemplate restTemplate = new RestTemplate();
            restTemplate.postForEntity(url("/api/items/201/checkout?asyncFail=server"),
                    new HttpEntity<>("{}", headers(corrId)), String.class);
        });

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatusCode());
        String responseBody = ex.getResponseBodyAsString();
        JsonNode body = JSON.readTree(responseBody);
        assertEquals(corrId, ex.getResponseHeaders().getFirst(CorrelationIdFilter.HEADER_NAME));
        assertEquals(corrId, body.get("correlationId").asText());
        assertEquals("SERVER_OTHER", body.get("errorCategory").asText());
        assertEquals("internal server error", body.get("reason").asText());
        // 该串只存在于工作线程抛出的异常 message 中；未脱敏必然直接出现在响应里
        assertFalse(responseBody.contains(secret),
                "internal async exception detail must not leak to caller");
        assertFalse(responseBody.contains("password"),
                "internal async exception message must not leak to caller");

        RouteMetrics after = metricsRegistry.metricsFor(ROUTE);
        assertEquals(noneBefore, after.getCount(ErrorClass.NONE),
                "a failed async job must not be counted as success");
        assertEquals(serverBefore + 1, after.getCount(ErrorClass.SERVER_ERROR),
                "async server failure must be counted as SERVER_ERROR");
        assertEquals(totalBefore + 1, after.getCount());

        log.info("async server-failure check: route={}, correlationId={}, errorClass=SERVER_ERROR, masked=true",
                ROUTE, corrId);
    }

    @Test
    void asyncSuccessRemainsCountedAsSuccessAndCarriesWorkerCorrelationId() {
        String corrId = "async-success-001";
        RouteMetrics before = metricsRegistry.metricsFor(ROUTE);
        long totalBefore = before.getCount();
        long noneBefore = before.getCount(ErrorClass.NONE);

        ResponseEntity<String> response = new RestTemplate().postForEntity(
                url("/api/items/202/checkout"),
                new HttpEntity<>("{}", headers(corrId)), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("job-for-202-" + corrId),
                "worker thread must observe the same correlation id");

        RouteMetrics after = metricsRegistry.metricsFor(ROUTE);
        assertEquals(noneBefore + 1, after.getCount(ErrorClass.NONE));
        assertEquals(totalBefore + 1, after.getCount());

        log.info("async success check: route={}, correlationId={}, errorClass=NONE", ROUTE, corrId);
    }
}
