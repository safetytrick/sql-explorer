package com.github.safetytrick.sqlexplorer.model;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;

import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;

public final class CapturedRate {

    private static final double SECONDS_PER_DAY = 86_400d;

    private CapturedRate() {
    }

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

    public static double rateDays(RuntimeMetrics metrics) {
        Double days = windowDays(metrics);
        return days == null ? 1d : Math.max(1d, days);
    }

    public static double perDay(long count, RuntimeMetrics metrics) {
        return count / rateDays(metrics);
    }

    public static double writesPerDay(TableReference table, Collection<? extends SqlProfile> profiles) {
        double total = 0;
        for (SqlProfile profile : profiles) {
            RuntimeMetrics metrics = profile.getRuntimeMetrics();
            if (metrics != null && WriteTargets.writes(profile, table)) {
                total += perDay(metrics.getExecuted(), metrics);
            }
        }
        return total;
    }
}
