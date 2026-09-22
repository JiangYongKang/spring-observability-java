package com.github.highcumontoa.springobservabilityjava;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 入站关联标识过滤器（在所有业务过滤器之前运行）。
 *
 * <p>职责：解析/生成关联标识 → 写入 {@link RequestContext} 与 MDC →
 * 回写响应头（标识本身 + 来源，两种情况可区分）→ finally 中清理，
 * 保证容器复用线程时不串号。
 */
@Component
@Order(1)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    private final CorrelationIdGenerator generator;

    public CorrelationIdFilter(CorrelationIdGenerator generator) {
        this.generator = generator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String[] source = new String[1];
        String correlationId = generator.resolveInbound(
                request.getHeader(RequestContext.HEADER_NAME), source);

        RequestContext.set(correlationId, source[0]);
        MDC.put(RequestContext.MDC_CORRELATION_ID, correlationId);
        // 请求生命周期内日志一律带默认值，错误发生时由异常处理器覆盖
        MDC.put(RequestContext.MDC_ERROR_CATEGORY, "NONE");

        // 在进入链路前回写，即使后续写响应体失败，头也已确定
        response.setHeader(RequestContext.HEADER_NAME, correlationId);
        response.setHeader(RequestContext.SOURCE_HEADER, source[0]);
        if (log.isDebugEnabled()) {
            log.debug("correlation id established source={} method={} uri={}",
                    source[0], request.getMethod(), request.getRequestURI());
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(RequestContext.MDC_CORRELATION_ID);
            MDC.remove(RequestContext.MDC_ROUTE_TEMPLATE);
            MDC.remove(RequestContext.MDC_ERROR_CATEGORY);
            RequestContext.clear();
        }
    }
}
