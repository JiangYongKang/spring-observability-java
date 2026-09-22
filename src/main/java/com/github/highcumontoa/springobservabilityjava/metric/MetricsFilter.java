package com.github.highcumontoa.springobservabilityjava.metric;

import com.github.highcumontoa.springobservabilityjava.RequestContext;
import com.github.highcumontoa.springobservabilityjava.error.ErrorCategory;
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
import org.springframework.web.servlet.HandlerMapping;

/**
 * 路由指标过滤器（在关联标识过滤器之后运行）。
 *
 * <p>要点：
 * <ul>
 *   <li>维度使用 Spring MVC 匹配后的路由模板（{@code /api/users/{id}}），
 *       而非原始 URI；匹配不到模板（无路由）直接跳过，不新建维度；</li>
 *   <li>错误分类优先取异常处理器写入的请求属性（同源一致），
 *       其次按最终响应状态归类；</li>
 *   <li>每个请求在 finally 中恰好记录一次。</li>
 * </ul>
 */
@Component
@Order(2)
public class MetricsFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(MetricsFilter.class);

    private final MetricsRegistry registry;

    public MetricsFilter(MetricsRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        long startNanos = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            recordOnce(request, response, System.nanoTime() - startNanos);
        }
    }

    private void recordOnce(HttpServletRequest request, HttpServletResponse response,
                            long durationNanos) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (!(pattern instanceof String routeTemplate) || routeTemplate.isBlank()) {
            // 无法归属路由：不污染、不新建维度
            log.debug("unmatched route, metric skipped uri={}", request.getRequestURI());
            return;
        }

        ErrorCategory category = resolveCategory(request, response);
        MDC.put(RequestContext.MDC_ROUTE_TEMPLATE, routeTemplate);
        MDC.put(RequestContext.MDC_ERROR_CATEGORY, category.name());

        RouteMetricKey key =
                new RouteMetricKey(request.getMethod(), routeTemplate, category);
        registry.record(key, durationNanos);

        log.info("request observed routeTemplate={} method={} status={} errorCategory={} durationNanos={}",
                routeTemplate, request.getMethod(), response.getStatus(),
                category.name(), durationNanos);
    }

    private ErrorCategory resolveCategory(HttpServletRequest request, HttpServletResponse response) {
        Object fromHandler = request.getAttribute(RequestContext.ATTR_ERROR_CATEGORY);
        if (fromHandler instanceof ErrorCategory category) {
            return category;
        }
        int status = response.getStatus();
        if (status >= 400 && status < 500) {
            return ErrorCategory.CLIENT_ERROR;
        }
        if (status >= 500 && status < 600) {
            return ErrorCategory.SERVER_ERROR;
        }
        return ErrorCategory.NONE;
    }
}
