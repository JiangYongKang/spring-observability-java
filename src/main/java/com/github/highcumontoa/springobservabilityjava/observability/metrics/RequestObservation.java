package com.github.highcumontoa.springobservabilityjava.observability.metrics;

/**
 * 同一次请求观测在过滤器与拦截器之间传递的请求属性键。
 */
final class RequestObservation {

    /** 请求开始时刻（纳秒），由观测过滤器在最外层设置，保证未匹配路由的请求也有真实起点。 */
    static final String START_NANOS_ATTR =
            RequestObservation.class.getName() + ".startNanos";
    /** 请求钉住的指标代次，由观测过滤器设置，拦截器据此把记录写进正确批次。 */
    static final String GENERATION_ATTR =
            RequestObservation.class.getName() + ".generation";
    /** 拦截器完成记录后置位；过滤器据此判断是否需要兜底补记 UNMAPPED 桶。 */
    static final String RECORDED_ATTR =
            RequestObservation.class.getName() + ".recorded";

    private RequestObservation() {
    }
}
