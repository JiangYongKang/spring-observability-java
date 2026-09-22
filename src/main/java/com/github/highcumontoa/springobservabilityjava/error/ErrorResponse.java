package com.github.highcumontoa.springobservabilityjava.error;

/** 对外错误响应体（不包含内部异常细节）。 */
public record ErrorResponse(
        String correlationId,
        int status,
        ErrorCategory category,
        String message
) {
}
