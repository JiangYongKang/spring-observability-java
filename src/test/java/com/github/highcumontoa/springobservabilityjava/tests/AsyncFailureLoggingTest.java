package com.github.highcumontoa.springobservabilityjava.tests;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.highcumontoa.springobservabilityjava.observability.web.AsyncWorkService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异步失败日志测试：请求触发的异步任务失败时，工作线程上必须打出 ERROR 日志，
 * 且该日志行携带同一个关联标识（MDC 在工作线程被还原）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AsyncFailureLoggingTest {

    @LocalServerPort
    private int port;

    @Autowired
    private AsyncWorkService asyncWorkService;

    @Test
    void asyncFailureLogOnWorkerThreadCarriesSameCorrelationId() {
        Logger workerLogger = (Logger) LoggerFactory.getLogger(AsyncWorkService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        workerLogger.addAppender(appender);
        try {
            String corrId = "async-log-fail-001";
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Correlation-ID", corrId);
            headers.setContentType(MediaType.APPLICATION_JSON);

            assertThrows(HttpServerErrorException.class, () -> new RestTemplate().postForEntity(
                    "http://localhost:" + port + "/api/items/300/checkout?asyncFail=server",
                    new HttpEntity<>("{}", headers), String.class));

            // 等待工作线程完成日志（任务失败后日志在 join 前打出，但此处仍做短暂轮询以消除调度抖动）
            List<ILoggingEvent> failureEvents = new CopyOnWriteArrayList<>();
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                failureEvents.clear();
                for (ILoggingEvent event : appender.list) {
                    if (event.getLevel() == Level.ERROR && event.getFormattedMessage().contains("async job failed")) {
                        failureEvents.add(event);
                    }
                }
                if (!failureEvents.isEmpty()) {
                    break;
                }
                Thread.onSpinWait();
            }

            assertFalse(failureEvents.isEmpty(), "worker thread must emit an ERROR log on async failure");
            ILoggingEvent event = failureEvents.get(0);
            assertEquals(corrId, event.getMDCPropertyMap().get("correlationId"),
                    "async worker failure log must carry the request correlation id");
            assertTrue(event.getThreadName().startsWith("obs-async-"),
                    "failure log must be emitted on the async pool worker thread");
        } finally {
            workerLogger.detachAppender(appender);
        }
    }
}
