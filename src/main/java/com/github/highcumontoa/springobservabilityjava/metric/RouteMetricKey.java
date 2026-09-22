package com.github.highcumontoa.springobservabilityjava.metric;

import com.github.highcumontoa.springobservabilityjava.error.ErrorCategory;

/** 路由指标维度键：HTTP 方法 + 路由模板 + 错误分类。 */
public record RouteMetricKey(String method, String routeTemplate, ErrorCategory errorCategory) {
}
