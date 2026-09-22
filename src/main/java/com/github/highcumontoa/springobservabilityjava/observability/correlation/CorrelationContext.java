package com.github.highcumontoa.springobservabilityjava.observability.correlation;

import org.slf4j.MDC;

/**
 * 请求关联标识的进程内载体。
 *
 * <p>使用普通 {@link ThreadLocal}（非 {@code InheritableThreadLocal}）：请求线程池会复用线程，
 * 父子继承会导致异步任务串号；跨线程透传由 {@link CorrelationTaskDecorator} 在任务提交时
 * 显式捕获、在执行线程显式还原，并在任务结束时还原线程原有上下文。</p>
 */
public final class CorrelationContext {

    /** 关联标识在日志 MDC 中的键。 */
    public static final String MDC_CORRELATION_ID = "correlationId";
    /** 关联标识来源在日志 MDC 中的键（INBOUND / GENERATED）。 */
    public static final String MDC_CORRELATION_SOURCE = "correlationSource";

    private static final ThreadLocal<Snapshot> HOLDER = new ThreadLocal<>();

    private CorrelationContext() {
    }

    /** 当前线程的关联标识；未进入请求上下文时返回 {@code null}。 */
    public static String currentId() {
        Snapshot snapshot = HOLDER.get();
        return snapshot == null ? null : snapshot.correlationId();
    }

    /** 当前线程关联标识的来源；未进入请求上下文时返回 {@code null}。 */
    public static CorrelationIdSource currentSource() {
        Snapshot snapshot = HOLDER.get();
        return snapshot == null ? null : snapshot.source();
    }

    /**
     * 在当前线程打开一个请求关联上下文；返回的 {@link Scope} 必须在请求处理结束时关闭，
     * 关闭后线程将恢复到进入前的上下文（对线程池复用安全）。
     */
    public static Scope open(String correlationId, CorrelationIdSource source) {
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        Snapshot previous = HOLDER.get();
        HOLDER.set(new Snapshot(correlationId, source));
        MDC.put(MDC_CORRELATION_ID, correlationId);
        MDC.put(MDC_CORRELATION_SOURCE, source.name());
        return () -> {
            if (previous == null) {
                HOLDER.remove();
                MDC.remove(MDC_CORRELATION_ID);
                MDC.remove(MDC_CORRELATION_SOURCE);
            } else {
                HOLDER.set(previous);
                MDC.put(MDC_CORRELATION_ID, previous.correlationId());
                MDC.put(MDC_CORRELATION_SOURCE, previous.source().name());
            }
        };
    }

    /** 捕获当前线程的关联上下文快照（供异步任务提交时使用）；没有上下文时返回 {@code null}。 */
    static Snapshot capture() {
        return HOLDER.get();
    }

    /** 关联上下文作用域，关闭时恢复进入前的线程状态。 */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    record Snapshot(String correlationId, CorrelationIdSource source) {
    }
}
