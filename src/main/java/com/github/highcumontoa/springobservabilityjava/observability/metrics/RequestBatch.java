package com.github.highcumontoa.springobservabilityjava.observability.metrics;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 请求与统计批次的归属关系工具：在请求<strong>进入时</strong>（过滤器层，对所有请求生效）
 * 把当时所属的批次句柄钉在请求属性上，之后无论请求处理多久、中途是否发生重置，
 * 完成时都计入这个进入时就定好的批次。
 *
 * <p>与 {@link RequestTiming} 同理：戳存在请求属性中，天然请求隔离，不涉及线程共享状态；
 * 重复钉批次时以第一次为准（异步重复派发的场景不会被覆盖）。</p>
 */
public final class RequestBatch {

    /** 请求属性中记录归属批次（{@link Generation} 句柄）的键。 */
    public static final String BATCH_ATTR = RequestBatch.class.getName() + ".batch";

    private RequestBatch() {
    }

    /** 在请求进入时钉上当前批次；已有归属时不覆盖，保证一次请求只归属一个批次。 */
    public static void attach(HttpServletRequest request, MetricsRegistry registry) {
        if (request.getAttribute(BATCH_ATTR) == null) {
            request.setAttribute(BATCH_ATTR, registry.currentBatch());
        }
    }

    /** 读取请求进入时钉下的归属批次；缺戳时防御性地按当前批次归属（等价于旧的即时语义）。 */
    public static Generation current(HttpServletRequest request, MetricsRegistry registry) {
        Object attr = request.getAttribute(BATCH_ATTR);
        if (attr instanceof Generation batch) {
            return batch;
        }
        return registry.currentBatch();
    }
}
