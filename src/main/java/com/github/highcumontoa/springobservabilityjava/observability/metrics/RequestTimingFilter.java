package com.github.highcumontoa.springobservabilityjava.observability.metrics;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 对所有入站请求在进入时盖计时戳、钉统计批次的过滤器。
 *
 * <p>必须先于路由匹配执行：未匹配处理器的请求不会进入 HandlerInterceptor 链，
 * 只有过滤器层的戳对它们仍然可见，从而让全局异常处理器能算出兜底分组的真实耗时，
 * 不再出现 UNMAPPED 请求耗时恒为 0 的情况。</p>
 *
 * <p>批次归属同样在进入时钉下（{@link RequestBatch}）：重置之前进入的请求，
 * 无论重置后多久才结束，都只计入重置前的旧批次，不会混入新批次。</p>
 */
public class RequestTimingFilter extends OncePerRequestFilter {

    private final MetricsRegistry metricsRegistry;

    public RequestTimingFilter(MetricsRegistry metricsRegistry) {
        this.metricsRegistry = metricsRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        RequestTiming.stamp(request);
        RequestBatch.attach(request, metricsRegistry);
        filterChain.doFilter(request, response);
    }
}
