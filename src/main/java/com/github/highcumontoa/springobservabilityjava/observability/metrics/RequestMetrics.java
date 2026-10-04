package com.github.highcumontoa.springobservabilityjava.observability.metrics;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 把单次 HTTP 请求与它"进入时所属的统计批次"绑定的门面。
 *
 * <p>{@link #begin} 在请求刚进入（过滤器层，先于路由匹配）时调用：</p>
 * <ul>
 *   <li>观测能力自身的内部端点（{@link ObservabilityPaths}，如查看/重置统计）被显式跳过——
 *       它们不领取批次、永不计数，因此重置后查看时新批次确实从零开始；</li>
 *   <li>其余请求（含未匹配路由的 404）立即领取当前代票据并存入请求属性，
 *       这一步即决定了它属于哪一批，与它何时结束无关。</li>
 * </ul>
 *
 * <p>{@link #finish} 在请求结束（拦截器 afterCompletion 或全局异常处理器兜底）时调用，
 * 把耗时与最终错误分类记回<strong>进入时的那一代</strong>。同一请求属性保证：
 * 匹配到路由的请求即使同时经过异常处理器也只记录一次；未匹配路由的请求由异常处理器补记，不遗漏。</p>
 */
public final class RequestMetrics {

    /** 请求属性键：存放进入时领取的 {@link RequestBatch}；内部端点不放置该属性。 */
    private static final String BATCH_ATTR = RequestMetrics.class.getName() + ".batch";

    private RequestMetrics() {
    }

    /**
     * 请求进入：盖计时戳、若非内部观测端点则领取当前代票据。
     * 对每个请求只生效一次（重复调用不会重复领取/占用名额）。
     */
    public static void begin(HttpServletRequest request, MetricsRegistry registry) {
        RequestTiming.stamp(request);
        if (request.getAttribute(BATCH_ATTR) != null) {
            return; // 每个请求只领取一次（含内部端点的"已跳过"占位）
        }
        if (ObservabilityPaths.isInternalObservabilityPath(request.getRequestURI())) {
            // 显式标记已处理，避免内部端点被任何后续环节计入业务指标
            request.setAttribute(BATCH_ATTR, SKIPPED);
            return;
        }
        request.setAttribute(BATCH_ATTR, registry.openBatch());
    }

    /** 内部观测端点使用的占位标记：存在即"已处理且不计数"。 */
    private static final Object SKIPPED = new Object();

    /**
     * 请求结束：幂等地把这次观测记回进入时的批次。
     *
     * @return 实际记录到的批次序号；内部端点（不计数）或尚未 begin 时返回 -1
     */
    public static long finish(HttpServletRequest request, String routeTemplate, ErrorClass errorClass) {
        Object attr = request.getAttribute(BATCH_ATTR);
        if (!(attr instanceof RequestBatch batch)) {
            return -1L;
        }
        long durationNanos = RequestTiming.elapsedNanos(request);
        long generationId = batch.generationId();
        batch.finish(routeTemplate, durationNanos, errorClass);
        return generationId;
    }
}
