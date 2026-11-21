package com.github.safetytrick.sqlexplorer.environment;

import java.time.Duration;

/**
 * MySQL's {@code max_execution_time} aborts only {@code SELECT}s.
 */
public record SamplingLimits(Duration maxExecutionTime, int maxRows) {

    public static final SamplingLimits DEFAULT = new SamplingLimits(Duration.ofSeconds(5), 10_000);

    public SamplingLimits {
        if (maxExecutionTime == null || maxExecutionTime.isNegative() || maxExecutionTime.isZero()) {
            throw new IllegalArgumentException("maxExecutionTime must be positive");
        }
        if (maxRows <= 0) {
            throw new IllegalArgumentException("maxRows must be positive");
        }
    }
}
