package com.github.highcumontoa.springobservabilityjava.observability.metrics;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定的请求耗时档位（直方图桶）。
 *
 * <p>档位集合在编译期写死、数量有界（{@link #values()}），不随请求量或 URL 数量增长；
 * 每个非负耗时经过 {@link #bucketOfNanos(long)} 都<strong>恰好</strong>落入一个档位：
 * 上界以下开、上界闭区间递增，最后一档 {@link #GE_1000MS} 覆盖所有 ≥1000ms 的长尾请求。</p>
 *
 * <p>分档规则（左闭右开，末档上不封顶）：</p>
 * <ol>
 *   <li>{@code [0, 1ms)}     → {@link #LT_1MS}</li>
 *   <li>{@code [1ms, 5ms)}   → {@link #LT_5MS}</li>
 *   <li>{@code [5ms, 10ms)}  → {@link #LT_10MS}</li>
 *   <li>{@code [10ms, 25ms)} → {@link #LT_25MS}</li>
 *   <li>{@code [25ms, 50ms)} → {@link #LT_50MS}</li>
 *   <li>{@code [50ms, 100ms)}→ {@link #LT_100MS}</li>
 *   <li>{@code [100ms, 1s)}  → {@link #LT_1000MS}</li>
 *   <li>{@code ≥1s}          → {@link #GE_1000MS}（慢请求长尾）</li>
 * </ol>
 */
public enum LatencyBucket {

    LT_1MS("LT_1MS", 0L, 1_000_000L),
    LT_5MS("LT_5MS", 1_000_000L, 5_000_000L),
    LT_10MS("LT_10MS", 5_000_000L, 10_000_000L),
    LT_25MS("LT_25MS", 10_000_000L, 25_000_000L),
    LT_50MS("LT_50MS", 25_000_000L, 50_000_000L),
    LT_100MS("LT_100MS", 50_000_000L, 100_000_000L),
    LT_1000MS("LT_1000MS", 100_000_000L, 1_000_000_000L),
    GE_1000MS("GE_1000MS", 1_000_000_000L, Long.MAX_VALUE);

    private final String label;
    private final long lowerInclusiveNanos;
    private final long upperExclusiveNanos;

    LatencyBucket(String label, long lowerInclusiveNanos, long upperExclusiveNanos) {
        this.label = label;
        this.lowerInclusiveNanos = lowerInclusiveNanos;
        this.upperExclusiveNanos = upperExclusiveNanos;
    }

    /**
     * 返回非负耗时唯一归属的档位。各档位区间首尾相接、互不重叠且覆盖 [0,+∞)，
     * 因此一次请求恰好落入一档。
     */
    public static LatencyBucket bucketOfNanos(long durationNanos) {
        if (durationNanos < 0) {
            throw new IllegalArgumentException("durationNanos must be non-negative");
        }
        for (LatencyBucket bucket : values()) {
            if (durationNanos < bucket.upperExclusiveNanos) {
                return bucket;
            }
        }
        return GE_1000MS; // Long.MAX_VALUE 上界本身不可达时的语义兜底
    }

    /** 稳定顺序的不可变档位视图，供指标快照端点序列化。 */
    public static List<LatencyBucket> ordered() {
        List<LatencyBucket> ordered = new ArrayList<>(values().length);
        for (LatencyBucket bucket : values()) {
            ordered.add(bucket);
        }
        return List.copyOf(ordered);
    }

    public String getLabel() {
        return label;
    }

    public long getLowerInclusiveNanos() {
        return lowerInclusiveNanos;
    }

    public long getUpperExclusiveNanos() {
        return upperExclusiveNanos;
    }
}
