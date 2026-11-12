package com.github.safetytrick.sqlexplorer.model;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;

import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;

/**
 * Turning Performance Schema's cumulative counters into a per-day rate.
 *
 * <p>WHAT THE NUMBER IS. A digest row counts executions since the digest table
 * was last reset, and says when the statement was first and last seen in that
 * period. Nothing records <em>when</em> in between it ran, so the only rate the
 * data supports is an average: count ÷ (lastSeen − firstSeen). It cannot show
 * a nightly batch as a nightly batch — 30,000 inserts at 02:00 every night read
 * as 30,000 a day, evenly spread. Every caller labels it as an average.
 *
 * <p>THE ONE-DAY FLOOR. A statement first seen an hour before the capture has
 * an hour-long window; dividing by it would extrapolate one hour's burst into
 * a daily figure 24 times too large. The window is therefore floored at one
 * day: a statement seen for less than a day reports its count as its daily
 * rate, which is exactly what was observed. The same floor covers a statement
 * whose first or last seen is missing.
 *
 * <p>PER STATEMENT, NOT PER TABLE. Each statement is divided by its own
 * window, and a table's rate is the sum. A statement introduced by last week's
 * deploy ran for a week, not for the months the table's oldest statement
 * covers; dividing its count by the longer window would understate it.
 */
public final class CapturedRate {

    private static final double SECONDS_PER_DAY = 86_400d;

    private CapturedRate() {
    }

    /** lastSeen − firstSeen in days, or null when either end is unknown. */
    public static Double windowDays(RuntimeMetrics metrics) {
        if (metrics == null) {
            return null;
        }
        LocalDateTime first = metrics.getFirstSeen();
        LocalDateTime last = metrics.getLastSeen();
        if (first == null || last == null || last.isBefore(first)) {
            return null;
        }
        return Duration.between(first, last).toSeconds() / SECONDS_PER_DAY;
    }

    /** The divisor: the window, floored at one day. */
    public static double rateDays(RuntimeMetrics metrics) {
        Double days = windowDays(metrics);
        return days == null ? 1d : Math.max(1d, days);
    }

    /** {@code count} spread over the statement's window; see the class note. */
    public static double perDay(long count, RuntimeMetrics metrics) {
        return count / rateDays(metrics);
    }

    /** Executions per day of every writing statement in {@code profiles}, summed. */
    public static double writesPerDay(Collection<? extends SqlProfile> profiles) {
        double total = 0;
        for (SqlProfile profile : profiles) {
            RuntimeMetrics metrics = profile.getRuntimeMetrics();
            if (metrics != null && QueryKind.of(profile).isWrite()) {
                total += perDay(metrics.getExecuted(), metrics);
            }
        }
        return total;
    }
}
