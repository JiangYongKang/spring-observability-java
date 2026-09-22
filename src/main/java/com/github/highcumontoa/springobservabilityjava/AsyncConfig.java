package com.github.highcumontoa.springobservabilityjava;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 配置带 MDC 传播的异步任务执行器。
 *
 * <p>固定较小线程数 + 有界队列，便于在测试中验证“线程复用不串号”。
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "observationExecutor")
    public Executor observationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("obs-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(new MdcAwareTaskDecorator());
        executor.initialize();
        return executor;
    }
}
