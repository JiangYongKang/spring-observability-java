package com.github.highcumontoa.springobservabilityjava.observability.correlation;

import org.springframework.core.task.TaskDecorator;

/**
 * 异步任务装饰器：在任务<strong>提交线程</strong>捕获关联标识快照，在<strong>执行线程</strong>还原，
 * 任务结束后恢复执行线程原有的 MDC/ThreadLocal 状态。
 *
 * <p>不使用 {@code InheritableThreadLocal}：线程池工作线程只在创建时继承一次提交线程的值，
 * 线程复用时会把旧请求的标识带给新任务，造成串号。</p>
 */
public class CorrelationTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        CorrelationContext.Snapshot captured = CorrelationContext.capture();
        if (captured == null) {
            return runnable;
        }
        return () -> {
            try (CorrelationContext.Scope ignored =
                         CorrelationContext.open(captured.correlationId(), captured.source())) {
                runnable.run();
            }
        };
    }
}
