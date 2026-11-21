package com.github.safetytrick.sqlexplorer.evidence.sink;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

public record CaptureContext(
    Origin origin,
    String sourceRef,
    Instant capturedAt,
    int rowCount,
    LocalDateTime observedFrom,
    LocalDateTime observedTo,
    Map<String, String> server) {

    public static final String ORIGIN_TABLE = "sys.x$statement_analysis";

    public enum Origin {
        PERFORMANCE_SCHEMA,
        CSV_EXPORT,
        PARQUET_COPY
    }

    public Map<String, String> asMetadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        put(metadata, "sqlexplorer.origin", origin == null ? null : origin.name());
        put(metadata, "sqlexplorer.originTable", ORIGIN_TABLE);
        put(metadata, "sqlexplorer.sourceRef", sourceRef);
        put(metadata, "sqlexplorer.capturedAt", capturedAt == null ? null : capturedAt.toString());
        put(metadata, "sqlexplorer.rowCount", String.valueOf(rowCount));
        put(metadata, "sqlexplorer.observedFrom", observedFrom == null ? null : observedFrom.toString());
        put(metadata, "sqlexplorer.observedTo", observedTo == null ? null : observedTo.toString());
        if (server != null) {
            server.forEach((key, value) -> put(metadata, "mysql." + key, value));
        }
        return metadata;
    }

    public static CaptureContext fromMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.keySet().stream().noneMatch(key -> key.startsWith("sqlexplorer."))) {
            return null;
        }
        Map<String, String> server = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (key.startsWith("mysql.")) {
                server.put(key.substring("mysql.".length()), value);
            }
        });
        Integer rowCount = parse(metadata.get("sqlexplorer.rowCount"), Integer::parseInt);
        return new CaptureContext(
            parse(metadata.get("sqlexplorer.origin"), Origin::valueOf),
            metadata.get("sqlexplorer.sourceRef"),
            parse(metadata.get("sqlexplorer.capturedAt"), Instant::parse),
            rowCount == null ? 0 : rowCount,
            parse(metadata.get("sqlexplorer.observedFrom"), LocalDateTime::parse),
            parse(metadata.get("sqlexplorer.observedTo"), LocalDateTime::parse),
            Map.copyOf(server));
    }

    private static <T> T parse(String value, Function<String, T> as) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return as.apply(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void put(Map<String, String> into, String key, String value) {
        if (value != null && !value.isBlank()) {
            into.put(key, value);
        }
    }
}
