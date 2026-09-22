package com.github.highcumontoa.springobservabilityjava.observability.metrics;

/**
 * 错误的粗粒度分类，日志 / 指标 / HTTP 响应三处共用同一套归一结果。
 */
public enum ErrorClass {
    /** 2xx 成功。 */
    NONE,
    /** 4xx，调用方可通过修改请求重试。 */
    CLIENT_ERROR,
    /** 5xx，服务端内部失败。 */
    SERVER_ERROR;

    /** 按对外 HTTP 状态码归一错误类别，保证指标维度与响应状态一致。 */
    public static ErrorClass fromStatus(int httpStatus) {
        if (httpStatus >= 400 && httpStatus < 500) {
            return CLIENT_ERROR;
        }
        if (httpStatus >= 500) {
            return SERVER_ERROR;
        }
        return NONE;
    }
}
