package com.github.highcumontoa.springobservabilityjava.observability.config;

import com.github.highcumontoa.springobservabilityjava.observability.correlation.CorrelationIdFilter;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.MetricsRegistry;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.RequestMetricsFilter;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.RouteMetricsInterceptor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 可观测组件装配：请求批次过滤器（最先执行，对所有请求生效：进入时盖戳并领取统计批次，
 * 观测自身的内部端点除外）、关联标识过滤器（最高优先级，先于一切业务逻辑）、
 * 路由指标拦截器（只统计匹配到处理器的请求链）。
 */
@Configuration
public class WebObservabilityConfig implements WebMvcConfigurer {

    @Bean
    public MetricsRegistry metricsRegistry() {
        return new MetricsRegistry();
    }

    @Bean
    public RequestMetricsFilter requestMetricsFilter(MetricsRegistry registry) {
        return new RequestMetricsFilter(registry);
    }

    @Bean
    public FilterRegistrationBean<RequestMetricsFilter> requestMetricsFilterRegistration(
            RequestMetricsFilter filter) {
        FilterRegistrationBean<RequestMetricsFilter> registration = new FilterRegistrationBean<>(filter);
        // 先于关联标识过滤器，使整段请求处理（含过滤器链本身）都在计时与批次归属范围内
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationFilterRegistration(
            CorrelationIdFilter filter) {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    public RouteMetricsInterceptor routeMetricsInterceptor(MetricsRegistry registry) {
        return new RouteMetricsInterceptor(registry);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(routeMetricsInterceptor(metricsRegistry()));
    }
}
