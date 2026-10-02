package com.github.highcumontoa.springobservabilityjava.observability.config;

import com.github.highcumontoa.springobservabilityjava.observability.correlation.CorrelationIdFilter;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.MetricsRegistry;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.RequestTimingFilter;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.RouteMetricsInterceptor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 可观测组件装配：计时过滤器（最先执行，对所有请求生效）、关联标识过滤器（最高优先级，
 * 先于一切业务逻辑）、路由指标拦截器（只统计匹配到处理器的请求链）。
 */
@Configuration
public class WebObservabilityConfig implements WebMvcConfigurer {

    @Bean
    public MetricsRegistry metricsRegistry() {
        return new MetricsRegistry();
    }

    @Bean
    public RequestTimingFilter requestTimingFilter() {
        return new RequestTimingFilter();
    }

    @Bean
    public FilterRegistrationBean<RequestTimingFilter> requestTimingFilterRegistration(
            RequestTimingFilter filter) {
        FilterRegistrationBean<RequestTimingFilter> registration = new FilterRegistrationBean<>(filter);
        // 先于关联标识过滤器，使整段请求处理（含过滤器链本身）都在计时范围内
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
