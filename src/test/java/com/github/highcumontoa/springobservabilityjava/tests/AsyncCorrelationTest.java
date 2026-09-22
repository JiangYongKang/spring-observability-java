package com.github.highcumontoa.springobservabilityjava.tests;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.springobservabilityjava.observability.correlation.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异步与并发边界测试：
 * <ul>
 *   <li>每个请求的关联标识原样透传到 {@code @Async} 工作线程（响应体内嵌异步任务看到的标识）；</li>
 *   <li>高并发 + 线程池复用下各请求标识互不串号；</li>
 *   <li>并发结束后指标总数与请求总数一致，按路由模板聚合。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AsyncCorrelationTest {

    private static final Logger log = LoggerFactory.getLogger(AsyncCorrelationTest.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private com.github.highcumontoa.springobservabilityjava.observability.metrics.MetricsRegistry metricsRegistry;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void asyncWorkerInheritsSameCorrelationId() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        String correlationId = "async-trace-001";
        HttpHeaders headers = new HttpHeaders();
        headers.set(CorrelationIdFilter.HEADER_NAME, correlationId);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/api/items/100/checkout"), new HttpEntity<>("{}", headers), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(correlationId, response.getHeaders().getFirst(CorrelationIdFilter.HEADER_NAME));
        JsonNode body = JSON.readTree(response.getBody());
        // asyncJob 由工作线程生成，内含工作线程看到的关联标识，必须与请求标识一致
        assertEquals("job-for-100-" + correlationId, body.get("asyncJob").asText());

        log.info("async propagation check: route=/api/items/{{itemId}}/checkout, errorClass=NONE, correlationId={}",
                correlationId);
    }

    @Test
    void concurrentRequestsNeverBleedCorrelationIdsAcrossThreads() throws Exception {
        int clients = 24;
        int iterations = 20;
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(clients);
        Set<String> failures = ConcurrentHashMap.newKeySet();
        List<String> allIds = new ArrayList<>();
        // 每个客户端固定一个标识；并发远大于核心线程数(2)，强制线程池工作线程被反复复用
        for (int c = 0; c < clients; c++) {
            final int clientIndex = c;
            final String clientCorrId = "concurrent-client-" + clientIndex;
            pool.submit(() -> {
                try {
                    start.await();
                    RestTemplate restTemplate = new RestTemplate();
                    for (int i = 0; i < iterations; i++) {
                        HttpHeaders requestHeaders = new HttpHeaders();
                        requestHeaders.set(CorrelationIdFilter.HEADER_NAME, clientCorrId);
                        ResponseEntity<String> response = restTemplate.postForEntity(
                                url("/api/items/" + clientIndex + "/checkout"),
                                new HttpEntity<>("{}", requestHeaders), String.class);
                        String headerId = response.getHeaders().getFirst(CorrelationIdFilter.HEADER_NAME);
                        JsonNode body = JSON.readTree(response.getBody());
                        String asyncId = body.get("asyncJob").asText();
                        if (!clientCorrId.equals(headerId)
                                || !asyncId.equals("job-for-" + clientIndex + "-" + clientCorrId)) {
                            failures.add("client=" + clientCorrId + " header=" + headerId + " asyncJob=" + asyncId);
                        }
                        synchronized (allIds) {
                            allIds.add(headerId);
                        }
                    }
                } catch (Exception e) {
                    failures.add(clientCorrId + ":" + e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "concurrent requests should finish in time");
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        assertTrue(failures.isEmpty(), "correlation id bleed detected: " + failures);
        assertEquals(clients * iterations, allIds.size());
        assertEquals(clients, new HashSet<>(allIds).size(), "only the distinct client ids may appear");

        long routeCount = metricsRegistry.metricsFor("/api/items/{itemId}/checkout").getCount();
        assertEquals(clients * iterations, routeCount,
                "metrics count must equal total concurrent requests");

        log.info("concurrency propagation check: route=/api/items/{{itemId}}/checkout, "
                        + "errorClass=NONE, requests={}, distinctCorrelationIds={}, metricCount={}",
                clients * iterations, clients, routeCount);
    }
}
