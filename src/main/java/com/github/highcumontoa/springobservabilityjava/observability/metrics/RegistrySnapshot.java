package com.github.highcumontoa.springobservabilityjava.observability.metrics;

import java.util.List;

/**
 * 一次重置所冻结的上一统计批次（generation）的完整视图。
 *
 * <p>批次对新进入的请求关闭，但重置前已进入的在途请求完成时仍计入本批次——
 * 包括那些在重置时刻还没有任何计数、因迟到完成才首次建桶的路由。因此本视图
 * （连同 {@link #routes()}）都是对旧批次计数器的<strong>实时视图</strong>：
 * 每次访问都从冻结批次现取，迟到的完成与新建的路由桶都会反映到后续读取中，
 * 保证每笔请求恰好属于一个批次、任何一批都不漏算。</p>
 *
 * <p>注意：重置接口的<strong>响应体</strong>在返回那一刻序列化，是重置时刻的切面；
 * 之后才结束的在途请求不可能出现在已发出的响应体里，但会留存在本视图背后的旧批次数据中。</p>
 */
public final class RegistrySnapshot {

    private final Generation frozen;
    private final long resetAtEpochMillis;

    private RegistrySnapshot(Generation frozen, long resetAtEpochMillis) {
        this.frozen = frozen;
        this.resetAtEpochMillis = resetAtEpochMillis;
    }

    /** 包装一个刚被冻结的批次。 */
    static RegistrySnapshot from(Generation frozen, long resetAtEpochMillis) {
        return new RegistrySnapshot(frozen, resetAtEpochMillis);
    }

    /** 被冻结批次的序号。 */
    public long generation() {
        return frozen.id();
    }

    /** 被冻结批次开始累计的时间（epoch 毫秒）。 */
    public long startedAtEpochMillis() {
        return frozen.startedAtEpochMillis();
    }

    /** 批次被冻结（重置发生）的时间（epoch 毫秒）。 */
    public long resetAtEpochMillis() {
        return resetAtEpochMillis;
    }

    /** 冻结批次当前的全部路由视图（实时读取，含迟到完成新建的路由桶）。 */
    public List<RouteSnapshot> routes() {
        return frozen.routes().stream()
                .map(metrics -> RouteSnapshot.from(frozen.id(), metrics))
                .toList();
    }
}
