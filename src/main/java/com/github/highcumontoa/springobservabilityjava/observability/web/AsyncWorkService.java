package com.github.highcumontoa.springobservabilityjava.observability.web;

import java.util.concurrent.CompletableFuture;
import com.github.highcumontoa.springobservabilityjava.observability.correlation.CorrelationContext;
import com.github.highcumontoa.springobservabilityjava.observability.error.ExceptionClassifier;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.ErrorClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 请求触发的异步处理服务。独立 Bean 保证 {@code @Async} 代理生效；
 * 工作线程上的关联标识由线程池上的 CorrelationTaskDecorator 还原。
 *
 * <p>失败处理与同步请求共用同一套 {@link ExceptionClassifier} 归因：异步任务失败时，
 * 在<strong>工作线程</strong>上按归一结果打结构化日志（关联标识仍来自 MDC），
 * 再把异常透传给返回的 {@link CompletableFuture}。若请求线程 {@code join} 等待了结果，
 * 异常会被全局异常处理器按同一分类转成响应状态与脱敏文案，指标也按该响应状态归类——
 * 日志、指标、响应三处对同一次失败的分类一致，且成功/失败可明确区分。</p>
 */
@Service
public class AsyncWorkService {

    private static final Logger log = LoggerFactory.getLogger(AsyncWorkService.class);

    private final ExceptionClassifier classifier;

    public AsyncWorkService(ExceptionClassifier classifier) {
        this.classifier = classifier;
    }

    @Async
    public CompletableFuture<String> processCheckout(String itemId, boolean fail) {
        String id = CorrelationContext.currentId();
        var source = CorrelationContext.currentSource();
        log.info("async processing item: itemId={}, correlationId={}, source={}", itemId, id, source);
        if (fail) {
            // 内部细节（伪造的连接凭据）只能出现在服务端日志，绝不能透传给调用方
            IllegalStateException failure = new IllegalStateException(
                    "async checkout downstream failed: dbToken=async-secret-9f8e7d connection refused");
            ExceptionClassifier.Classification result = classifier.classify(failure);
            // 工作线程日志：关联标识 + 归一分类，与请求线程最终看到的分类同源
            if (result.errorClass() == ErrorClass.SERVER_ERROR) {
                log.error("async job failed: itemId={}, errorCategory={}, httpStatus={}",
                        itemId, result.category(), result.httpStatus(), failure);
            } else {
                log.warn("async job failed: itemId={}, errorCategory={}, httpStatus={}",
                        itemId, result.category(), result.httpStatus(), failure);
            }
            return CompletableFuture.failedFuture(failure);
        }
        return CompletableFuture.completedFuture("job-for-" + itemId + "-" + id);
    }
}
