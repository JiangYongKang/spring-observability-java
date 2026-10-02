package com.github.highcumontoa.springobservabilityjava.observability.metrics;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 全量请求的指标观测过滤器（在关联标识过滤器之后、一切业务逻辑之前执行）。
 *
 * <p>职责：</p>
 * <ol>
 *   <li>在最外层记录请求开始时刻并钉住当前指标代次（{@link MetricsRegistry#acquire()}），
 *       因此<strong>未匹配到路由的请求也有真实耗时</strong>，不再出现 UNMAPPED 桶耗时恒为 0；</li>
 *   <li>请求结束时若 {@link RouteMetricsInterceptor} 已按路由模板记录，则只释放代次；</li>
 *   <li>否则（未匹配处理器等）用真实耗时、以最终响应状态对应的错误分类，补记固定
 *       {@link MetricsRegistry#UNMAPPED_TEMPLATE} 桶——不丢计数，也不按原始 URI 新建维度。</li>
 * </ol>
 *
 * <p>计时起点在过滤器而非拦截器，覆盖了"找不到处理器 → 拦截器链根本不执行"的路径；
 * 钉住的代次保证并发重置时该请求只会落进它进入时所在的统计批次。</p>
 */
public class MetricsObservationFilter extends OncePerRequestFilter {

    /** 指标自管理端点（查看/重置）不参与自身观测，避免重置请求把自己计入被冻结的老批次。 */
    private static final String INTERNAL_METRICS_PREFIX = "/internal/metrics";

    private static final Logger log = LoggerFactory.getLogger(MetricsObservationFilter.class);

    private final MetricsRegistry registry;

    public MetricsObservationFilter(MetricsRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.equals(INTERNAL_METRICS_PREFIX)
                || uri.startsWith(INTERNAL_METRICS_PREFIX + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startNanos = System.nanoTime();
        MetricsGeneration generation = registry.acquire();
        request.setAttribute(RequestObservation.START_NANOS_ATTR, startNanos);
        request.setAttribute(RequestObservation.GENERATION_ATTR, generation);
        try {
            filterChain.doFilter(request, response);
        } finally {
            try {
                if (request.getAttribute(RequestObservation.RECORDED_ATTR) != Boolean.TRUE) {
                    long durationNanos = Math.max(0L, System.nanoTime() - startNanos);
                    ErrorClass errorClass = ErrorClass.fromStatus(response.getStatus());
                    registry.record(generation, MetricsRegistry.UNMAPPED_TEMPLATE,
                            durationNanos, errorClass);
                    log.info("request observed: route={}, method={}, status={}, errorClass={}, durationNanos={}",
                            MetricsRegistry.UNMAPPED_TEMPLATE, request.getMethod(),
                            response.getStatus(), errorClass, durationNanos);
                }
            } finally {
                registry.release(generation);
            }
        }
    }
}
