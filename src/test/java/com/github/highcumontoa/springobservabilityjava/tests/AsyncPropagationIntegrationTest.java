package com.github.highcumontoa.springobservabilityjava.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * 异步执行边界：
 * 请求触发的异步工作线程日志与响应必须携带同一关联标识；
 * 仅 2 个工作线程承接大量并发请求，验证线程复用下不串号。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AsyncPropagationIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AsyncPropagationIntegrationTest.class);

    private final ObjectMapper mapper = new ObjectMapper();

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void asyncWorkerCarriesSameCorrelationIdAsResponse() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Correlation-Id", "async-trace-1");
        ResponseEntity<String> response = rest.exchange(url("/api/async?label=ok"),
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        JsonNode body = mapper.readTree(response.getBody());

        assertThat(body.get("correlationId").asText()).isEqualTo("async-trace-1");
        assertThat(body.get("result").asText()).isEqualTo("ok:async-trace-1");
        // 工作线程（obs-async-*）上的日志由应用输出，MDC 中 correlationId 相同
        log.info("async boundary verified correlationId=async-trace-1 "
                + "routeTemplate=/api/async errorCategory=NONE");
    }

    @Test
    void concurrentAsyncRequestsDoNotMixIdsAcrossPooledThreads() throws Exception {
        // 工作线程池固定 2 个线程，64 个不同标识并发，强制反复复用
        int total = 64;
        ExecutorService clients = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < total; i++) {
            final String cid = "async-cid-" + i;
            futures.add(clients.submit(() -> {
                start.await();
                HttpHeaders headers = new HttpHeaders();
                headers.add("X-Correlation-Id", cid);
                ResponseEntity<String> response = rest.exchange(url("/api/async?label=ok"),
                        HttpMethod.GET, new HttpEntity<>(headers), String.class);
                JsonNode body = mapper.readTree(response.getBody());
                String returned = body.get("correlationId").asText();
                if (!cid.equals(returned)) {
                    return "mismatch sent=" + cid + " got=" + returned;
                }
                return null;
            }));
        }
        start.countDown();
        List<String> mismatches = new ArrayList<>();
        for (Future<String> f : futures) {
            String result = f.get();
            if (result != null) {
                mismatches.add(result);
            }
        }
        clients.shutdown();

        log.info("async concurrency finished requests={} workerPoolSize=2 mismatches={} "
                + "routeTemplate=/api/async errorCategory=NONE", total, mismatches.size());
        assertThat(mismatches).isEmpty();
    }

    @Test
    void generatedIdAlsoPropagatesToAsyncWorker() throws Exception {
        // 不带入站头：服务端生成的标识同样要传播进异步线程
        ResponseEntity<String> response =
                rest.exchange(url("/api/async?label=ok"), HttpMethod.GET,
                        new HttpEntity<>(new HttpHeaders()), String.class);
        String headerId = response.getHeaders().getFirst("X-Correlation-Id");
        JsonNode body = mapper.readTree(response.getBody());

        assertThat(headerId).isNotBlank();
        assertThat(body.get("correlationId").asText()).isEqualTo(headerId);
        assertThat(response.getHeaders().getFirst("X-Correlation-Id-Source"))
                .isEqualTo("generated");
        log.info("generated id propagated to async worker correlationId={} "
                + "routeTemplate=/api/async errorCategory=NONE", headerId);
    }
}
