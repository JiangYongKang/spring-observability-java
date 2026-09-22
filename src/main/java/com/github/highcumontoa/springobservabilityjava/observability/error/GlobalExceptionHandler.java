package com.github.highcumontoa.springobservabilityjava.observability.error;

import com.github.highcumontoa.springobservabilityjava.observability.correlation.CorrelationContext;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.ErrorClass;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.MetricsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * 全局异常处理：分类结果（{@link ExceptionClassifier}）同时用于结构化日志与对外响应。
 *
 * <p>响应体只包含关联标识、错误类别与固定脱敏文案；异常类型、消息、堆栈只写服务端日志，
 * 不向调用方泄露内部实现细节。响应状态码即指标拦截器读取的状态，三处分类天然一致。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ExceptionClassifier classifier;
    private final MetricsRegistry metricsRegistry;

    public GlobalExceptionHandler(ExceptionClassifier classifier, MetricsRegistry metricsRegistry) {
        this.classifier = classifier;
        this.metricsRegistry = metricsRegistry;
    }

    /**
     * 未匹配处理器的请求不会进入 HandlerInterceptor 链，因此在此显式计入固定 UNMAPPED 桶，
     * 保证"无法归属路由的请求"既不被遗漏，也不会按原始 URI 新建维度。
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiError> handleNoHandler(NoHandlerFoundException ex) {
        ExceptionClassifier.Classification result = classifier.classify(ex);
        metricsRegistry.record(MetricsRegistry.UNMAPPED_TEMPLATE, 0L, result.errorClass());
        log.warn("request failed: route={}, errorCategory={}, httpStatus={}",
                MetricsRegistry.UNMAPPED_TEMPLATE, result.category(), result.httpStatus());
        return ResponseEntity.status(result.httpStatus())
                .body(new ApiError(CorrelationContext.currentId(), result.category().name(),
                        result.publicReason()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleAny(Exception ex) {
        ExceptionClassifier.Classification result = classifier.classify(ex);

        // 日志携带关联标识（MDC）与细分类别；服务端错误保留完整堆栈用于排障
        if (result.errorClass() == ErrorClass.SERVER_ERROR) {
            log.error("request failed: errorCategory={}, httpStatus={}",
                    result.category(), result.httpStatus(), ex);
        } else {
            log.warn("request failed: errorCategory={}, httpStatus={}, reason={}",
                    result.category(), result.httpStatus(), result.publicReason());
        }

        String correlationId = CorrelationContext.currentId();
        ApiError body = new ApiError(correlationId, result.category().name(), result.publicReason());
        return ResponseEntity.status(result.httpStatus()).body(body);
    }
}
