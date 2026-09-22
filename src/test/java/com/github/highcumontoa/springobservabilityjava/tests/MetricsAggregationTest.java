package com.github.highcumontoa.springobservabilityjava.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.highcumontoa.springobservabilityjava.error.ErrorCategory;
import com.github.highcumontoa.springobservabilityjava.metric.MetricsRegistry;
import com.github.highcumontoa.springobservabilityjava.metric.RouteMetricAggregate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 指标测试：路径参数归一到同一路由模板；未匹配路由不新建维度；
 * 高并发下计数与请求总数严格一致（不丢失、不重复）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MetricsAggregationTest {

    private static final Logger log = LoggerFactory.getLogger(MetricsAggregationTest.class);

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

    @Test
    void differentPathParamsShareOneRouteTemplate() {
        rest.getForEntity(url("/api/users/aaa"), String.class);
        rest.getForEntity(url("/api/users/bbb"), String.class);
        rest.getForEntity(url("/api/users/ccc"), String.class);

        RouteMetricAggregate ok = registry.find("GET", "/api/users/{id}", ErrorCategory.NONE);
        assertThat(ok).isNotNull();
        assertThat(ok.getCount()).isEqualTo(3);
        assertThat(registry.cardinality()).isEqualTo(1);
        log.info("routeTemplate=/api/users/{{}} count={} cardinality={} errorCategory=NONE",
                ok.getCount(), registry.cardinality());
    }

    @Test
    void unmatchedRoutesDoNotCreateDimensions() {
        rest.getForEntity(url("/totally/unknown/1"), String.class);
        rest.getForEntity(url("/totally/unknown/2"), String.class);

        assertThat(registry.cardinality()).isZero();
        log.info("unmatched routes recorded cardinality={} (must be 0)", registry.cardinality());
    }

    @Test
    void errorCategoriesAreSeparateDimensions() {
        rest.getForEntity(url("/api/users/x"), String.class);
        rest.getForEntity(url("/api/errors/client"), String.class);
        rest.getForEntity(url("/api/errors/server"), String.class);

        assertThat(registry.find("GET", "/api/users/{id}", ErrorCategory.NONE).getCount())
                .isEqualTo(1);
        assertThat(registry.find("GET", "/api/errors/client", ErrorCategory.CLIENT_ERROR)
                .getCount()).isEqualTo(1);
        assertThat(registry.find("GET", "/api/errors/server", ErrorCategory.SERVER_ERROR)
                .getCount()).isEqualTo(1);
        // 同一路由的成功与失败不可互相串维度
        assertThat(registry.find("GET", "/api/errors/client", ErrorCategory.NONE)).isNull();
        log.info("errorCategory dimensions separated cardinality={}", registry.cardinality());
    }

    @Test
    void concurrentRecordCountEqualsRequestTotalNoLossNoDuplication() throws Exception {
        int threads = 16;
        int perThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    rest.getForEntity(url("/api/users/value-" + i), String.class);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        RouteMetricAggregate aggregate =
                registry.find("GET", "/api/users/{id}", ErrorCategory.NONE);
        int expected = threads * perThread;
        log.info("concurrent requests={} metricCount={} routeTemplate=/api/users/{{}} errorCategory=NONE",
                expected, aggregate.getCount());
        assertThat(aggregate.getCount()).isEqualTo(expected);
    }

    @Test
    void registryPrimitiveIsCorrectUnderConcurrentDirectWrites() throws Exception {
        // 绕过 HTTP，直接压并发原语，进一步验证不丢失不重复
        int threads = 8;
        int perThread = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        var key = new com.github.highcumontoa.springobservabilityjava.metric.RouteMetricKey(
                "GET", "/load/test", ErrorCategory.NONE);

        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    registry.record(key, 100L);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(registry.find("GET", "/load/test", ErrorCategory.NONE).getCount())
                .isEqualTo(threads * perThread);
        log.info("direct concurrent writes expected={} actual={}",
                threads * perThread,
                registry.find("GET", "/load/test", ErrorCategory.NONE).getCount());
    }
}
