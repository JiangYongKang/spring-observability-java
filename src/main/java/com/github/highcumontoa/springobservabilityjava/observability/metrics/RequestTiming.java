
package com.github.highcumontoa.springobservabilityjava.observability.metrics;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 请求计时起点工具：在对所有请求都生效的过滤器中盖戳，拦截器与异常处理器读取同一处起点，
 * 保证未匹配路由（不经过 HandlerInterceptor）的兜底请求也能统计到真实耗时，而非恒为 0。
 *
 * <p>{@link System#nanoTime()} 仅用于测量时间差，与挂钟时间无关；戳存在请求属性中，
 * 天然请求隔离，不涉及任何线程共享状态。</p>
 */
public final class RequestTiming {

    /** 请求属性中记录开始时间（{@link System#nanoTime()}）的键。 */
    public static final String START_NANOS_ATTR = RequestTiming.class.getName() + ".startNanos";

    private RequestTiming() {
    }

    /** 在请求开始时记录起始时间戳（纳秒）。重复盖戳时以第一次为准，避免中途覆盖。 */
    public static void stamp(HttpServletRequest request) {
        if (request.getAttribute(START_NANOS_ATTR) == null) {
            request.setAttribute(START_NANOS_ATTR, System.nanoTime());
        }
    }

    /** 读取从盖戳到当前的耗时（纳秒，非负）；缺少戳时回退为 0，绝不返回负值。 */
    public static long elapsedNanos(HttpServletRequest request) {
        Object attr = request.getAttribute(START_NANOS_ATTR);
        if (attr instanceof Long startNanos) {
            return Math.max(0L, System.nanoTime() - startNanos);
        }
        return 0L;
    }
}
