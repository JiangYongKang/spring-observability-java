package com.github.highcumontoa.springobservabilityjava.observability.metrics;

/**
 * 单个请求在<strong>进入时</strong>领取的批次票据：把它与"当时那一代计数器（{@link Generation}）"
 * 绑定。请求多久之后才结束都无关——结束时通过本票据记回<strong>进入时的那一代</strong>，
 * 因此"重置前进入、重置后才结束"的在途请求只会出现在被冻结的旧批次，绝不会漏入新批次。
 *
 * <p>票据不可变、每请求一个实例、只存于该请求的属性中，不涉及任何线程间共享。
 * 一次进入对应唯一一次 {@link #finish}，先写计数再释放该代的在途占比，
 * 因此重置排空旧代时，最后一个在途请求的计数一定先于冻结快照落定。</p>
 */
final class RequestBatch {

    private final Generation generation;
    private final long enteredAtNanos;
    private boolean finished;

    private RequestBatch(Generation generation, long enteredAtNanos) {
        this.generation = generation;
        this.enteredAtNanos = enteredAtNanos;
    }

    /** 在请求进入时调用：在"当前代"上占一个在途名额，并把本请求归属到该代。 */
    static RequestBatch open(Generation current) {
        current.enter();
        return new RequestBatch(current, System.nanoTime());
    }

    /** 本请求进入时所属的批次序号（便于日志/断言核对归属）。 */
    long generationId() {
        return generation.id();
    }

    long enteredAtNanos() {
        return enteredAtNanos;
    }

    /**
     * 请求结束时把这次观测记回<strong>进入时</strong>的那一代，并释放占比。
     * 与之后发生过多少次重置无关；每个票据只生效一次（不重复计数）。
     */
    synchronized void finish(String routeTemplate, long durationNanos, ErrorClass errorClass) {
        if (finished) {
            return;
        }
        finished = true;
        generation.metricsFor(routeTemplate).record(durationNanos, errorClass);
        generation.exit();
    }
}
