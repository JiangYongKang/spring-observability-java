package com.github.highcumontoa.springobservabilityjava.observability.metrics;

/**
 * 观测能力自身的内部端点判定。
 *
 * <p>统计查看（{@code GET /internal/metrics}）与统计重置
 * （{@code POST /internal/metrics/reset}）只是"操作统计"的入口，它们本身不是被观测的
 * 业务请求：若把这两个操作计入批次，刚重置完去查看时，新一批里会混入"重置 + 查看"这两条
 * 记录，导致批次不是从零开始。因此这些路径必须从业务指标中排除（既不计数，也不建维度）。</p>
 */
public final class ObservabilityPaths {

    /** 内部观测端点的统一前缀。 */
    public static final String INTERNAL_PREFIX = "/internal/";

    private ObservabilityPaths() {
    }

    /**
     * 该请求是否命中观测能力自身的内部端点（按请求路径前缀判定，对未匹配路由也成立）。
     *
     * @param requestUri 原始请求路径（{@code HttpServletRequest#getRequestURI}），
     *                   为 {@code null} 时返回 {@code false}
     */
    public static boolean isInternalObservabilityPath(String requestUri) {
        return requestUri != null
                && (requestUri.equals("/internal/metrics") || requestUri.startsWith(INTERNAL_PREFIX));
    }
}
