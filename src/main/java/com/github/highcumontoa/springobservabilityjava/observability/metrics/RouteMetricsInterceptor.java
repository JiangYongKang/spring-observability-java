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
 * <p>计时起点来自 {@link RequestTiming}（过滤器层盖戳，对所有请求生效），与兜底分组读取
 * 同一处起点，保证耗时口径统一。</p>
 *
 * <p>批次归属按<strong>进入时刻</strong>判定：过滤器层已在请求进入时把所属批次钉在请求属性上
 * （{@link RequestBatch}），此处完成记录时计入该批次。因此统计重置之前已进入的请求，
 * 即使在重置之后才完成，也只留在被冻结的旧批次里，不会混入重置后的新批次。</p>
 *
 * <p>操作统计自身的入口（{@code /internal/**}）在配置层被排除在本拦截器之外，
 * 查看/重置统计的请求不会计入任何批次。</p>
 */
public class RouteMetricsInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RouteMetricsInterceptor.class);

    private final MetricsRegistry registry;

    public RouteMetricsInterceptor(MetricsRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 拦截器只覆盖匹配到处理器的请求；此处补戳仅为防御，统一起点与批次已由 RequestTimingFilter 钉好
        RequestTiming.stamp(request);
        RequestBatch.attach(request, registry);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        long durationNanos = RequestTiming.elapsedNanos(request);

        String template = resolveRouteTemplate(request);
        ErrorClass errorClass = ErrorClass.fromStatus(response.getStatus());
        // 计入"进入时"钉下的批次：重置前进入的请求即使现在才完成，也只留在旧批次
        registry.record(RequestBatch.current(request, registry), template, durationNanos, errorClass);

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
