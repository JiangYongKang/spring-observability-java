package com.github.highcumontoa.springobservabilityjava;

/**
 * 请求级关联标识上下文（基于 ThreadLocal）。
 *
 * <p>仅在请求线程及其显式提交的异步任务中可见；过滤器在请求结束时必须调用
 * {@link #clear()}，Tomcat 线程池复用线程时不会残留上一请求的标识。
 */
public final class RequestContext {

    /** 入站/出站关联标识头名。 */
    public static final String HEADER_NAME = "X-Correlation-Id";

    /** 标识来源响应头：区分外部沿用与服务端生成。 */
    public static final String SOURCE_HEADER = "X-Correlation-Id-Source";

    public static final String SOURCE_INBOUND = "inbound";
    public static final String SOURCE_GENERATED = "generated";

    /** MDC 中的键，日志 pattern 直接引用。 */
    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_ROUTE_TEMPLATE = "routeTemplate";
    public static final String MDC_ERROR_CATEGORY = "errorCategory";

    /**
     * 请求属性键：异常处理器归一后的错误分类（{@link ErrorCategory} 名称）。
     * 指标过滤器读取该属性作为唯一分类来源，保证日志/指标/响应一致。
     */
    public static final String ATTR_ERROR_CATEGORY = "observability.errorCategory";

    private static final ThreadLocal<String> CORRELATION_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> SOURCE = new ThreadLocal<>();

    private RequestContext() {
    }

    public static void set(String correlationId, String source) {
        CORRELATION_ID.set(correlationId);
        SOURCE.set(source);
    }

    public static String getCorrelationId() {
        return CORRELATION_ID.get();
    }

    public static String getSource() {
        return SOURCE.get();
    }

    public static void clear() {
        CORRELATION_ID.remove();
        SOURCE.remove();
    }
}
