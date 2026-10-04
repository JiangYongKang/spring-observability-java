package com.github.highcumontoa.springobservabilityjava.observability.metrics;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 每个入站请求最早执行的观测过滤器：盖计时戳，并在<strong>进入时</strong>领取统计批次
 * （{@link RequestMetrics#begin}）。
 *
 * <p>必须先于路由匹配执行：进入时刻即决定该请求归属哪一批，且未匹配处理器的请求不会进入
 * HandlerInterceptor 链，只有过滤器层的票据/计时戳对它们仍然可见，从而让全局异常处理器
 * 能把兜底请求按真实耗时记回正确批次。观测自身的内部端点在此被直接排除，不领取批次、不计数。</p>
 */
public class RequestMetricsFilter extends OncePerRequestFilter {

    private final MetricsRegistry registry;

    public RequestMetricsFilter(MetricsRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        RequestMetrics.begin(request, registry);
        filterChain.doFilter(request, response);
    }
}
