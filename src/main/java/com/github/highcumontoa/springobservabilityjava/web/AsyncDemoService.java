package com.github.highcumontoa.springobservabilityjava.web;

import com.github.highcumontoa.springobservabilityjava.RequestContext;
import com.github.highcumontoa.springobservabilityjava.error.ApiException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 请求触发的异步执行：在工作线程中读取关联标识并记录日志。
 * 关联标识的跨线程传播完全由 {@code MdcAwareTaskDecorator} 完成。
 */
@Service
public class AsyncDemoService {

    private static final Logger log = LoggerFactory.getLogger(AsyncDemoService.class);

    private final Executor observationExecutor;

    public AsyncDemoService(@Qualifier("observationExecutor") Executor observationExecutor) {
        this.observationExecutor = observationExecutor;
    }

    public CompletableFuture<String> runAsyncStep(String label) {
        return CompletableFuture.supplyAsync(() -> {
            // 跨线程传播的是 MDC（MdcAwareTaskDecorator），而非请求 ThreadLocal
            String cid = MDC.get(RequestContext.MDC_CORRELATION_ID);
            log.info("async step executing label={} thread={}", label, Thread.currentThread().getName());
            if (cid == null) {
                // 装饰器失效时这里会触发，正常链路不应发生
                throw new IllegalStateException("correlation id missing on async thread");
            }
            if ("fail".equals(label)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "async validation failed: " + label);
            }
            return label + ":" + cid;
        }, observationExecutor);
    }
}
