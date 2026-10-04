package com.github.highcumontoa.springobservabilityjava.observability.metrics;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 按路由模板聚合请求指标的拦截器。
 *
 * <p>路由模板取自 {@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE}（如
 * {@code /api/items/{itemId}}），不同路径参数归入同一维度；取不到模板（未匹配处理器）
 * 时计入 {@link MetricsRegistry#UNMAPPED_TEMPLATE} 固定桶，绝不使用原始 URI 新建维度。</p>
 *
 * <p>错误分类以最终对外 HTTP 状态码为准（{@link ErrorClass#fromStatus(int)}），因此无论异常
 * 是否被包装、是否被全局处理器处理，指标中的错误分类与调用方看到的状态始终一致。</p>
 *
 * <p>批次归属与计时起点都在请求进入时（{@link RequestMetricsFilter}）确定：此处结束时只负责
 * 把结果记回<strong>进入时的那一代</strong>。因此重置前进入、重置后才结束的请求仍计入旧批次，
 * 不会漏入新批次。{@link RequestMetrics#finish} 幂等：匹配到路由的请求即便同时经过全局异常
 * 处理器，也只记录一次。</p>
 */
public class RouteMetricsInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RouteMetricsInterceptor.class);

    private final MetricsRegistry registry;

    public RouteMetricsInterceptor(MetricsRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 批次与计时戳已由最先执行的 RequestMetricsFilter 在进入时盖好；此处兜底，保证即使过滤器装配缺失也不漏
        RequestMetrics.begin(request, registry);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        String template = resolveRouteTemplate(request);
        ErrorClass errorClass = ErrorClass.fromStatus(response.getStatus());

        // 记回进入时的批次（内部观测端点返回 -1，不计数）；耗时口径与兜底分组同取自 RequestTiming
        long generationId = RequestMetrics.finish(request, template, errorClass);
        if (generationId < 0) {
            return;
        }
        long durationNanos = RequestTiming.elapsedNanos(request);

        // 单行结构化访问日志：关联标识来自 MDC，批次号、路由模板与错误分类与指标维度完全一致
        log.info("request observed: generation={}, route={}, method={}, status={}, errorClass={}, durationNanos={}",
                generationId,
                template == null ? MetricsRegistry.UNMAPPED_TEMPLATE : template,
                request.getMethod(), response.getStatus(), errorClass, durationNanos);
    }

    private String resolveRouteTemplate(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof String value && !value.isBlank() ? value : null;
    }
}
