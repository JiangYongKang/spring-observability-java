package com.github.highcumontoa.springobservabilityjava.observability.metrics;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 按路由模板聚合请求指标的拦截器（仅覆盖成功匹配到处理器的请求）。
 *
 * <p>路由模板取自 {@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE}（如
 * {@code /api/items/{itemId}}），不同路径参数归入同一维度；取不到模板（未匹配处理器）
 * 时拦截器链不执行，由 {@link MetricsObservationFilter} 兜底计入
 * {@link MetricsRegistry#UNMAPPED_TEMPLATE} 固定桶，绝不使用原始 URI 新建维度。</p>
 *
 * <p>错误分类以最终对外 HTTP 状态码为准（{@link ErrorClass#fromStatus(int)}），因此无论异常
 * 是否被包装、是否被全局处理器处理，指标中的错误分类与调用方看到的状态始终一致。
 * 开始时刻与指标代次由 {@link MetricsObservationFilter} 在最外层钉好，这里直接读取，
 * 保证与兜底路径同一计时起点、同一统计批次。</p>
 */
public class RouteMetricsInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RouteMetricsInterceptor.class);

    private final MetricsRegistry registry;

    public RouteMetricsInterceptor(MetricsRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Object startAttr = request.getAttribute(RequestObservation.START_NANOS_ATTR);
        long startNanos = startAttr instanceof Long value ? value : System.nanoTime();
        Object pinnedAttr = request.getAttribute(RequestObservation.GENERATION_ATTR);
        if (!(pinnedAttr instanceof MetricsGeneration generation)) {
            // 装配中 MetricsObservationFilter 恒在拦截器之外并先设置代次；
            // 到这里说明装配被破坏，安全起见不写指标（不能凭空创建代次引用）。
            log.warn("missing pinned metrics generation; skip recording route observation");
            return;
        }
        String template = resolveRouteTemplate(request);
        ErrorClass errorClass = ErrorClass.fromStatus(response.getStatus());
        long durationNanos = Math.max(0L, System.nanoTime() - startNanos);

        // 写入过滤器在请求进入时钉住的批次
        registry.record(generation, template, durationNanos, errorClass);
        // 告知过滤器该请求已按路由记录，避免兜底重复计数
        request.setAttribute(RequestObservation.RECORDED_ATTR, Boolean.TRUE);

        // 单行结构化访问日志：关联标识来自 MDC，路由模板与错误分类与指标维度完全一致
        log.info("request observed: route={}, method={}, status={}, errorClass={}, durationNanos={}",
                template == null ? MetricsRegistry.UNMAPPED_TEMPLATE : template,
                request.getMethod(), response.getStatus(), errorClass, durationNanos);
    }

    private String resolveRouteTemplate(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof String value && !value.isBlank() ? value : null;
    }
}
