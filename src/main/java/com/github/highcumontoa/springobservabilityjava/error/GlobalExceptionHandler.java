package com.github.highcumontoa.springobservabilityjava.error;

import com.github.highcumontoa.springobservabilityjava.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：任何逃出控制器的异常都在此归一。
 *
 * <p>一致性保证：同一次计算产出的 {@link ErrorCategory} 同时用于
 * <ol>
 *   <li>MDC/错误日志（关联标识 + 路由模板 + 错误分类同一条日志可见）；</li>
 *   <li>请求属性，供指标过滤器聚合；</li>
 *   <li>响应体 category 字段。</li>
 * </ol>
 * 内部堆栈仅进日志；5xx 响应体不包含任何内部异常细节。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ExceptionClassifier classifier;

    public GlobalExceptionHandler(ExceptionClassifier classifier) {
        this.classifier = classifier;
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ErrorResponse> handle(Throwable throwable, HttpServletRequest request) {
        HttpStatus status = classifier.toStatus(throwable);
        ErrorCategory category = classifier.classify(throwable);
        String correlationId = RequestContext.getCorrelationId();
        String routeTemplate = String.valueOf(request.getAttribute(
                bestMatchingPatternAttribute()));
        String message = classifier.safeMessage(throwable, status, correlationId);

        // 唯一分类结果写入 MDC（错误日志）与请求属性（指标过滤器读取）
        MDC.put(RequestContext.MDC_ERROR_CATEGORY, category.name());
        request.setAttribute(RequestContext.ATTR_ERROR_CATEGORY, category);

        if (category == ErrorCategory.SERVER_ERROR) {
            // 堆栈等内部细节只进日志
            log.error("request failed category={} status={} routeTemplate={}",
                    category.name(), status.value(), routeTemplate, throwable);
        } else {
            log.warn("request failed category={} status={} routeTemplate={} reason={}",
                    category.name(), status.value(), routeTemplate, message);
        }

        ErrorResponse body = new ErrorResponse(correlationId, status.value(), category, message);
        return ResponseEntity.status(status).body(body);
    }

    private static String bestMatchingPatternAttribute() {
        // 与 MetricsFilter 使用同一个 Spring MVC 属性，避免硬编码不一致
        return "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern";
    }
}
