package com.github.highcumontoa.springobservabilityjava.observability.web;

import java.util.concurrent.CompletableFuture;
import com.github.highcumontoa.springobservabilityjava.observability.correlation.CorrelationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 请求触发的异步处理服务。独立 Bean 保证 {@code @Async} 代理生效；
 * 工作线程上的关联标识由线程池上的 CorrelationTaskDecorator 还原。
 */
@Service
public class AsyncWorkService {

    private static final Logger log = LoggerFactory.getLogger(AsyncWorkService.class);

    @Async
    public CompletableFuture<String> processCheckout(String itemId) {
        String id = CorrelationContext.currentId();
        var source = CorrelationContext.currentSource();
        log.info("async processing item: itemId={}, correlationId={}, source={}", itemId, id, source);
        return CompletableFuture.completedFuture("job-for-" + itemId + "-" + id);
    }
}
