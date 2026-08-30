package com.github.safetytrick.sqlexplorer.evidence.sink;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where a written profile file came from, recorded in the file itself.
 *
 * <p>Every corpus sql-explorer holds originates in
 * {@code sys.x$statement_analysis}, but not always directly: a CSV is an
 * export of that table, and a Parquet file is a copy of a CSV or of the table.
 * Which of those a file is, and <em>when</em> it was captured, decides what can
 * honestly be claimed about it — so it has to travel with the file.
 *
 * <p>The alternative is looking the facts up at read time, and that is the
 * trap this exists to avoid. {@code performance_schema_digests_size} and
 * {@code performance_schema_max_digest_length} applied on the source server at
 * the moment of capture. Reading them off a live connection and attaching them
 * to a file exported months ago, possibly from another machine, produces a
 * confident wrong answer — worse than saying nothing, because it looks like
 * information (sqlx-sy5, sqlx-n01).
 *
 * <p>The observed window matters for a second reason. Performance Schema
 * accumulates until the server restarts, so consecutive samples of one source
 * normally overlap. When a later sample's earliest {@code firstSeen} is after
 * an earlier sample's latest {@code lastSeen}, the digest table was reset in
 * between and that interval went unrecorded. A file that states its own window
 * makes that detectable without parsing it back out of profile ids.
 *
 * @param origin       how this file relates to the origin table
 * @param sourceRef    which source produced it
 * @param capturedAt   when the rows were read
 * @param rowCount     how many profiles were written
 * @param observedFrom earliest {@code firstSeen} across those rows, or null.
 *                     Zone-less, because that is what Performance Schema
 *                     reports and inventing a zone here would be a guess
 * @param observedTo   latest {@code lastSeen} across those rows, or null
 * @param server       capture-time facts about the source server. Empty is
 *                     honest and means unknown; it must never be filled in
 *                     later from a different connection.
 */
public record CaptureContext(
    Origin origin,
    String sourceRef,
    Instant capturedAt,
    int rowCount,
    LocalDateTime observedFrom,
    LocalDateTime observedTo,
    Map<String, String> server) {

    /** The origin table every corpus ultimately comes from. */
    public static final String ORIGIN_TABLE = "sys.x$statement_analysis";

    /** How far a file sits from {@link #ORIGIN_TABLE}. */
    public enum Origin {
        /** Read straight from the table on a live server. */
        PERFORMANCE_SCHEMA,
        /** A tab-separated export of the table. */
        CSV_EXPORT,
        /** Written by sql-explorer from one of the above. */
        PARQUET_COPY
    }

    /**
     * Flattened for Parquet's file-level key/value metadata, which is a
     * {@code Map<String,String>} and carries no types.
     *
     * <p>Keys are namespaced so a reader can tell our metadata from the
     * writer's own, and nulls are omitted rather than written as "null" — an
     * absent key reads as unknown, which is the distinction the whole record
     * exists to preserve.
     */
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

    private static void put(Map<String, String> into, String key, String value) {
        if (value != null && !value.isBlank()) {
            into.put(key, value);
        }
    }
}
