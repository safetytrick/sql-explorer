package com.github.safetytrick.sqlexplorer.model;

import java.util.Locale;
import java.util.Set;

import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;

public enum QueryKind {
    READ, WRITE, OTHER, UNKNOWN;

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
