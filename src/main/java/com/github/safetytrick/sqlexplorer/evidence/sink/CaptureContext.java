package com.github.safetytrick.sqlexplorer.evidence.sink;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

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
 * @param origin       how the rows reached sql-explorer — the last hop of
 *                     the chain from {@link #ORIGIN_TABLE}. Not what kind of
 *                     file this is: a Parquet file written from a live read
 *                     records PERFORMANCE_SCHEMA, because that is what decides
 *                     whether the settings below describe this data. That the
 *                     file itself is a Parquet copy is self-evident from being
 *                     one, and recording it instead would lose the only fact
 *                     the reader cannot recover
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

    /**
     * How rows reached sql-explorer, and so how far they sit from
     * {@link #ORIGIN_TABLE}. Each step weakens what can be claimed: a direct
     * read is describable by the server it came from, an export is describable
     * only by what the export recorded, and a copy of an export is one further
     * removed again.
     */
    public enum Origin {
        /** Read straight from the table on a live server — no hops. */
        PERFORMANCE_SCHEMA,
        /** A tab-separated export of the table — one hop. */
        CSV_EXPORT,
        /** Read back from a Parquet file sql-explorer wrote — two or more. */
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

    /**
     * Read provenance back out of a footer, or {@code null} when the file
     * carries none.
     *
     * <p>Null rather than an empty context, deliberately: "no provenance was
     * recorded" and "provenance was recorded and said nothing" are different
     * claims, and a reader deciding what it may assert about a file has to be
     * able to tell them apart. A file written before this existed is the
     * former.
     *
     * <p>Individual fields are independently optional for the same reason. An
     * unparseable value is dropped rather than defaulted — a wrong instant
     * would order the archive wrongly, which is worse than an unorderable
     * capture the reader can simply set aside.
     */
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
