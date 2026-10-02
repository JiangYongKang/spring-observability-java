package com.github.highcumontoa.springobservabilityjava.observability.metrics;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 对所有入站请求盖计时戳的过滤器。
 *
 * <p>必须先于路由匹配执行：未匹配处理器的请求不会进入 HandlerInterceptor 链，
 * 只有过滤器层的戳对它们仍然可见，从而让全局异常处理器能算出兜底分组的真实耗时，
 * 不再出现 UNMAPPED 请求耗时恒为 0 的情况。</p>
 */
public class RequestTimingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        RequestTiming.stamp(request);
        filterChain.doFilter(request, response);
    }
}
