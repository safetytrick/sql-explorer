package com.github.safetytrick.sqlexplorer.environment;

import java.time.Duration;

/**
 * The limits every query SQL Explorer runs against table data must observe
 * (sqlx-n5j.4). Part of the contract of {@link Environment#samplingTarget()}:
 * a feature that samples gets these with the server, and
 * {@link SamplingTarget#openSession()} applies the ones the server can enforce.
 *
 * <ul>
 *   <li><b>Read-only session.</b> {@code SET SESSION TRANSACTION READ ONLY}:
 *       the server itself refuses a write, whatever the statement.</li>
 *   <li><b>{@code maxExecutionTime}.</b> {@code SET SESSION max_execution_time}:
 *       MySQL aborts a {@code SELECT} that runs longer. It applies to
 *       {@code SELECT} only, which is all a sampling query may be.</li>
 *   <li><b>{@code maxRows}.</b> Not enforceable by the server for every query
 *       shape, so it is the caller's to honour: walk a table by primary-key
 *       range ({@code WHERE pk > ? ORDER BY pk LIMIT ?}) in chunks no larger
 *       than this, never a full scan and never {@code ORDER BY RAND()}.</li>
 * </ul>
 *
 * @param maxExecutionTime the longest any one statement may run
 * @param maxRows          the most rows any one statement may read
 */
public record SamplingLimits(Duration maxExecutionTime, int maxRows) {

    /** Conservative on purpose: a sampling query that needs more than this should be split, not loosened. */
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
