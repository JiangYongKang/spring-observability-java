package com.github.highcumontoa.springobservabilityjava.observability.config;

import com.github.highcumontoa.springobservabilityjava.observability.correlation.CorrelationIdFilter;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.MetricsObservationFilter;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.MetricsRegistry;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.RouteMetricsInterceptor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 可观测组件装配：关联标识过滤器（最高优先级，先于一切业务逻辑），
 * 路由指标拦截器（只统计匹配到处理器的请求链）。
 */
@Configuration
public class WebObservabilityConfig implements WebMvcConfigurer {

    @Bean
    public MetricsRegistry metricsRegistry() {
        return new MetricsRegistry();
    }

    @Bean
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationFilterRegistration(
            CorrelationIdFilter filter) {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    public MetricsObservationFilter metricsObservationFilter(MetricsRegistry registry) {
        return new MetricsObservationFilter(registry);
    }

    @Bean
    public FilterRegistrationBean<MetricsObservationFilter> metricsFilterRegistration(
            MetricsObservationFilter filter) {
        FilterRegistrationBean<MetricsObservationFilter> registration =
                new FilterRegistrationBean<>(filter);
        // 紧随关联标识过滤器之后：计时与代次钉取覆盖全部请求，且日志行已带关联标识
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    public RouteMetricsInterceptor routeMetricsInterceptor(MetricsRegistry registry) {
        return new RouteMetricsInterceptor(registry);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(routeMetricsInterceptor(metricsRegistry()))
                // 指标自管理端点不参与自身观测
                .excludePathPatterns("/internal/metrics", "/internal/metrics/**");
    }
}
