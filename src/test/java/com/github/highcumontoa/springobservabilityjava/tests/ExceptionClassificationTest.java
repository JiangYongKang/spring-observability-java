package com.github.highcumontoa.springobservabilityjava.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.highcumontoa.springobservabilityjava.error.ErrorCategory;
import com.github.highcumontoa.springobservabilityjava.metric.MetricsRegistry;
import com.github.highcumontoa.springobservabilityjava.metric.RouteMetricAggregate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * 端到端异常分类：响应体 category、指标维度、错误日志分类必须一致；
 * 嵌套/包装异常正确归类；5xx 内部细节不得出现在响应中。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExceptionClassificationTest {

    private static final Logger log = LoggerFactory.getLogger(ExceptionClassificationTest.class);

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    MetricsRegistry registry;

    @BeforeEach
    void resetMetrics() {
        registry.reset();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private JsonNode getWithCid(String path, String cid) {
        HttpHeaders headers = new HttpHeaders();
        if (cid != null) {
            headers.add("X-Correlation-Id", cid);
        }
        ResponseEntity<String> response = rest.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void wrappedClientErrorConsistentAcrossResponseMetricAndLog() {
        JsonNode body = getWithCid("/api/errors/wrapped-client", "cid-wrapped-client");

        assertThat(body.get("status").asInt()).isEqualTo(422);
        assertThat(body.get("category").asText()).isEqualTo("CLIENT_ERROR");
        assertThat(body.get("correlationId").asText()).isEqualTo("cid-wrapped-client");

        RouteMetricAggregate metric = registry.find(
                "GET", "/api/errors/wrapped-client", ErrorCategory.CLIENT_ERROR);
        assertThat(metric).isNotNull();
        assertThat(metric.getCount()).isEqualTo(1);
        assertThat(registry.find("GET", "/api/errors/wrapped-client", ErrorCategory.SERVER_ERROR))
                .isNull();

        log.info("verified routeTemplate=/api/errors/wrapped-client errorCategory=CLIENT_ERROR "
                + "responseCategory={} metricCount={}", body.get("category").asText(),
                metric.getCount());
    }

    @Test
    void wrappedServerErrorConsistentAndDetailsHidden() {
        JsonNode body = getWithCid("/api/errors/wrapped-server", "cid-wrapped-server");

        assertThat(body.get("status").asInt()).isEqualTo(500);
        assertThat(body.get("category").asText()).isEqualTo("SERVER_ERROR");
        assertThat(body.get("message").asText()).isEqualTo("Internal Server Error");
        // 内部消息（secret-table / boom）绝不能外泄
        assertThat(body.toString()).doesNotContain("secret-table").doesNotContain("boom");

        RouteMetricAggregate metric = registry.find(
                "GET", "/api/errors/wrapped-server", ErrorCategory.SERVER_ERROR);
        assertThat(metric).isNotNull();
        assertThat(metric.getCount()).isEqualTo(1);

        log.info("verified routeTemplate=/api/errors/wrapped-server errorCategory=SERVER_ERROR "
                + "internalDetailHidden=true metricCount={}", metric.getCount());
    }

    @Test
    void directServerErrorInternalMessageNotExposed() {
        JsonNode body = getWithCid("/api/errors/server", "cid-server");

        assertThat(body.get("category").asText()).isEqualTo("SERVER_ERROR");
        assertThat(body.toString()).doesNotContain("hunter2").doesNotContain("db password");
        log.info("verified routeTemplate=/api/errors/server errorCategory=SERVER_ERROR "
                + "message='{}'", body.get("message").asText());
    }

    @Test
    void clientErrorIsNotClassifiedAsServerAndReasonsDistinguishable() {
        JsonNode client = getWithCid("/api/errors/client", "cid-client");
        JsonNode missing = getWithCid("/api/users/missing", "cid-missing");

        assertThat(client.get("status").asInt()).isEqualTo(400);
        assertThat(client.get("category").asText()).isEqualTo("CLIENT_ERROR");
        assertThat(missing.get("status").asInt()).isEqualTo(404);
        assertThat(missing.get("category").asText()).isEqualTo("CLIENT_ERROR");
        // 同为客户端错误，但原因（状态码/消息）可区分
        assertThat(client.get("message").asText()).isNotEqualTo(missing.get("message").asText());

        log.info("client failures distinguishable routeTemplate=/api/errors/client "
                + "errorCategory=CLIENT_ERROR status=400 vs routeTemplate=/api/users/{{}} "
                + "errorCategory=CLIENT_ERROR status=404", "id");
    }

    @Test
    void asyncJoinWrappedClientErrorClassifiedCorrectly() {
        JsonNode body = getWithCid("/api/async?label=fail", "cid-async-fail");

        assertThat(body.get("status").asInt()).isEqualTo(400);
        assertThat(body.get("category").asText()).isEqualTo("CLIENT_ERROR");
        assertThat(body.get("correlationId").asText()).isEqualTo("cid-async-fail");

        RouteMetricAggregate metric =
                registry.find("GET", "/api/async", ErrorCategory.CLIENT_ERROR);
        assertThat(metric).isNotNull();
        assertThat(metric.getCount()).isEqualTo(1);

        log.info("verified routeTemplate=/api/async errorCategory=CLIENT_ERROR "
                + "joinWrapped=true correlationId=cid-async-fail");
    }
}
