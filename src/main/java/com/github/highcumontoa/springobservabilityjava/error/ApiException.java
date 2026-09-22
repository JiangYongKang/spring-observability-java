package com.github.highcumontoa.springobservabilityjava.error;

import org.springframework.http.HttpStatus;

/** 业务可预期异常，携带稳定的 HTTP 状态与对外消息。 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
