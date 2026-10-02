package com.github.highcumontoa.springobservabilityjava.observability.metrics;

import java.util.List;

/**
 * 一次重置所冻结的上一统计批次（generation）的完整快照。
 */
public record RegistrySnapshot(
        long generation,
        long startedAtEpochMillis,
        long resetAtEpochMillis,
        List<RouteSnapshot> routes) {
}
