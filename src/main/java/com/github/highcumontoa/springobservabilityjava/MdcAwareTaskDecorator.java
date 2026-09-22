package com.github.highcumontoa.springobservabilityjava;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * 将任务<strong>提交时刻</strong>的 MDC 快照传播到执行线程。
 *
 * <p>关键点：在 {@code decorate}（提交线程）时拷贝上下文，而非在
 * {@code run}（工作线程）时读取；工作线程执行完毕后恢复其原有 MDC，
 * 保证线程池线程被复用时不会把上一个任务的关联标识带给下一个任务。
 */
public class MdcAwareTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // 提交线程上执行：捕获快照
        Map<String, String> contextSnapshot = MDC.getCopyOfContextMap();
        return () -> {
            // 工作线程上执行：保存旧值 -> 装入快照 -> 执行 -> 还原
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (contextSnapshot == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(contextSnapshot);
            }
            try {
                runnable.run();
            } finally {
                if (previous == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previous);
                }
            }
        };
    }
}
