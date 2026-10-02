package com.github.highcumontoa.springobservabilityjava.observability.metrics;

/**
 * 固定、有界的请求耗时分档。档位集合在编译期确定（共 8 档），每个请求
 * {@link #forNanos(long)} 恰好落入唯一一个档位，分档数量不随请求量或 URL 增长。
 *
 * <p>分档为左开右闭区间（首档含 0），边界精确到纳秒：</p>
 * <ol>
 *   <li>{@code LE_1MS}：{@code 0ns <= d <= 1ms}</li>
 *   <li>{@code LE_5MS}：{@code 1ms < d <= 5ms}</li>
 *   <li>{@code LE_10MS}：{@code 5ms < d <= 10ms}</li>
 *   <li>{@code LE_25MS}：{@code 10ms < d <= 25ms}</li>
 *   <li>{@code LE_50MS}：{@code 25ms < d <= 50ms}</li>
 *   <li>{@code LE_100MS}：{@code 50ms < d <= 100ms}</li>
 *   <li>{@code LE_500MS}：{@code 100ms < d <= 500ms}</li>
 *   <li>{@code GT_500MS}：{@code d > 500ms}</li>
 * </ol>
 */
public enum DurationBucket {

    LE_1MS("<=1ms", 1_000_000L),
    LE_5MS("1ms-5ms", 5_000_000L),
    LE_10MS("5ms-10ms", 10_000_000L),
    LE_25MS("10ms-25ms", 25_000_000L),
    LE_50MS("25ms-50ms", 50_000_000L),
    LE_100MS("50ms-100ms", 100_000_000L),
    LE_500MS("100ms-500ms", 500_000_000L),
    GT_500MS(">500ms", Long.MAX_VALUE);

    private final String label;
    private final long upperBoundNanos;

    DurationBucket(String label, long upperBoundNanos) {
        this.label = label;
        this.upperBoundNanos = upperBoundNanos;
    }

    /** 对外展示用的区间标签。 */
    public String label() {
        return label;
    }

    /** 该档位上界（纳秒，含）；{@link #GT_500MS} 为 {@link Long#MAX_VALUE}。 */
    public long upperBoundNanos() {
        return upperBoundNanos;
    }

    /**
     * 按耗时（纳秒）选择唯一档位。对任意非负耗时，返回值唯一确定，不会重复归属或无处归属。
     *
     * @throws IllegalArgumentException 耗时为负
     */
    public static DurationBucket forNanos(long durationNanos) {
        if (durationNanos < 0) {
            throw new IllegalArgumentException("durationNanos must be non-negative");
        }
        for (DurationBucket bucket : values()) {
            if (durationNanos <= bucket.upperBoundNanos) {
                return bucket;
            }
        }
        return GT_500MS; // 理论不可达：上界为 Long.MAX_VALUE，留作防御
    }
}
