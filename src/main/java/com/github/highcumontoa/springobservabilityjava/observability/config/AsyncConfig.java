package com.github.highcumontoa.springobservabilityjava.observability.config;

import java.util.concurrent.Executor;
import com.github.highcumontoa.springobservabilityjava.observability.correlation.CorrelationTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步执行配置：业务线程池应用 {@link CorrelationTaskDecorator}，使请求触发的
 * {@code @Async} / 线程池任务在工作线程上还原同一关联标识，并设为 {@code @Async} 默认执行器。
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    public static final String EXECUTOR_NAME = "observabilityTaskExecutor";

    @Bean(name = EXECUTOR_NAME)
    public ThreadPoolTaskExecutor observabilityTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("obs-async-");
        // 装饰器在任务提交时捕获调用线程的关联标识，而非依赖线程创建时的继承
        executor.setTaskDecorator(new CorrelationTaskDecorator());
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return observabilityTaskExecutor();
    }
}
