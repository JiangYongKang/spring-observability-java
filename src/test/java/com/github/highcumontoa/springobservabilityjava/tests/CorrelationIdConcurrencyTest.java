package com.github.highcumontoa.springobservabilityjava.tests;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
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
 * 关联标识集成测试：
 * 入站沿用 vs 生成替换可区分；高并发 + Tomcat 线程复用下标识不串号。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorrelationIdConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdConcurrencyTest.class);

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void inboundValidIdIsAcceptedVerbatim() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Correlation-Id", "trace-ABC_123.xyz");
        ResponseEntity<String> response = rest.exchange(url("/api/users/1"),
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getHeaders().getFirst("X-Correlation-Id"))
                .isEqualTo("trace-ABC_123.xyz");
        assertThat(response.getHeaders().getFirst("X-Correlation-Id-Source"))
                .isEqualTo("inbound");
        log.info("inbound accepted verbatim id={} source={}",
                response.getHeaders().getFirst("X-Correlation-Id"),
                response.getHeaders().getFirst("X-Correlation-Id-Source"));
    }

    @Test
    void missingIdIsGeneratedAndMarked() {
        ResponseEntity<String> response =
                rest.getForEntity(url("/api/users/2"), String.class);

        String id = response.getHeaders().getFirst("X-Correlation-Id");
        assertThat(id).isNotBlank();
        assertThat(UUID.fromString(id)).isNotNull();
        assertThat(response.getHeaders().getFirst("X-Correlation-Id-Source"))
                .isEqualTo("generated");
        log.info("missing inbound id generated id={} source={}", id,
                response.getHeaders().getFirst("X-Correlation-Id-Source"));
    }

    @Test
    void malformedIdIsReplacedAndDistinguishableFromAccepted() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Correlation-Id", "bad id with space"); // 含非法空格
        ResponseEntity<String> response = rest.exchange(url("/api/users/3"),
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        String id = response.getHeaders().getFirst("X-Correlation-Id");
        assertThat(id).isNotEqualTo("bad id with space");
        assertThat(response.getHeaders().getFirst("X-Correlation-Id-Source"))
                .isEqualTo("generated");
        log.info("malformed inbound id replaced original='bad id with space' new={}", id);
    }

    @Test
    void concurrentRequestsNeverMixUpCorrelationIds() throws Exception {
        int threads = 32;
        int perThread = 25; // 远多于 Tomcat 工作线程，强制线程复用
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> mismatches = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int threadIndex = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    String cid = "cid-" + threadIndex + "-" + i;
                    HttpHeaders headers = new HttpHeaders();
                    headers.add("X-Correlation-Id", cid);
                    ResponseEntity<String> response = rest.exchange(url("/api/users/k" + i),
                            HttpMethod.GET, new HttpEntity<>(headers), String.class);
                    String echoed = response.getHeaders().getFirst("X-Correlation-Id");
                    if (!cid.equals(echoed)) {
                        mismatches.add("sent=" + cid + " got=" + echoed);
                    }
                }
                return null;
            }));
        }

        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        log.info("concurrency finished requests={} mismatches={}",
                threads * perThread, mismatches.size());
        assertThat(mismatches).as("correlation id must not cross requests: %s", mismatches)
                .isEmpty();
    }
}
