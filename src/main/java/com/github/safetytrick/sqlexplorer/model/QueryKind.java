package com.github.safetytrick.sqlexplorer.model;

import java.util.Locale;
import java.util.Set;

import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;

/**
 * Whether a statement reads, writes, does something else, or could not be
 * told: the one place that question is answered. Rates, the topology split,
 * a table's write load and a profile's {@code isDml} all ask it here.
 *
 * <p>Computed when asked, from the profile's type and, for a statement whose
 * type is unknown, its rows affected:
 * <ul>
 *   <li>INSERT, UPDATE, DELETE and REPLACE write.</li>
 *   <li>SELECT reads.</li>
 *   <li>UNKNOWN writes if it changed any rows. A SELECT affects none, so any
 *       means a write. None proves nothing — a write can match no rows — so
 *       it stays unknown rather than being guessed a read.</li>
 *   <li>Anything else (SET, COMMIT, CALL, DDL) is other.</li>
 * </ul>
 *
 * <p>Profiles analysed before types were read past CTEs (analyzer version 6
 * and earlier) carry {@code WITH} and {@code (}. They are re-typed on their
 * next load; until then {@code (} reads and {@code WITH} is treated as
 * unknown, which is what it was.
 */
public enum QueryKind {
    READ, WRITE, OTHER, UNKNOWN;

    /** Types that change rows. REPLACE is an INSERT that may DELETE first. */
    public static final Set<String> WRITE_TYPES = Set.of("INSERT", "UPDATE", "DELETE", "REPLACE");

    public static QueryKind of(SqlProfile profile) {
        return of(profile.getType(), profile.getRuntimeMetrics());
    }

    public static QueryKind of(String type, RuntimeMetrics metrics) {
        return of(type, metrics == null ? 0 : metrics.getRowsAffected());
    }

    public static QueryKind of(String type, long rowsAffected) {
        String upper = type == null ? "UNKNOWN" : type.toUpperCase(Locale.ROOT);
        if (WRITE_TYPES.contains(upper)) {
            return WRITE;
        }
        return switch (upper) {
            case "SELECT", "(" -> READ;
            case "UNKNOWN", "WITH" -> rowsAffected > 0 ? WRITE : UNKNOWN;
            default -> OTHER;
        };
    }

    public boolean isWrite() {
        return this == WRITE;
    }

    public boolean isRead() {
        return this == READ;
    }
}
