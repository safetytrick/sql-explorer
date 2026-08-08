package com.github.safetytrick.sqlexplorer.profiles;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Filter/sort/page criteria for {@code POST /queries/search}. Every field is
 * optional; a missing field does not constrain the results. Times are accepted
 * in milliseconds (metrics are stored in picoseconds), selectivity as a 0..1
 * fraction (rows sent / rows scanned).
 */
public record QuerySearchRequest(
    /** Regex matched against {@link com.github.safetytrick.sqlexplorer.avro.SqlProfile#getQuery()}
     *  (case-insensitive, substring; an invalid pattern falls back to a literal search). */
    String queryRegex,
    Range avgTimeMs,
    Range maxTimeMs,
    Range avgRowsScanned,
    Range selectivity,
    Range rowsSorted,
    Activity activity,
    /** Statement types (SELECT, INSERT, …); "OTHER" matches any type outside {@link QuerySearchService#EXPLICIT_TYPES}. */
    List<String> types,
    DateRange firstSeen,
    DateRange lastSeen,
    List<String> tags,
    TagMode tagMode,
    Boolean untaggedOnly,
    /** Normalized schema names; a query in any listed schema matches. */
    List<String> schemas,
    /** Normalized schema names; a query that touches any of these is excluded. */
    List<String> excludeSchemas,
    /** Normalized {@code schema.table} keys (see SchemaTreeService keying). */
    List<String> tables,
    /** ANY = query references any selected table; ALL = it references all of them. */
    TableMode tableMode,
    /**
     * Normalized {@code schema.table} keys; a query that references any of
     * these is excluded. The table-level twin of {@link #excludeSchemas()},
     * and applied the same way — exclusion wins over any include, and it
     * applies whether or not {@link #tables()} narrows the search.
     */
    List<String> excludeTables,
    Flag fullScan,
    Flag tmpTable,
    Flag tmpDisk,
    Flag errors,
    Flag warnings,
    Flag truncated,
    Flag multiSchema,
    Boolean analyzerWarnings,
    Boolean analysisFailed,
    Boolean cleanAnalysis,
    Boolean analysisSkipped,
    /** Failure refinement: failed, not truncated, and the reason marks an analyzer vs parse failure. */
    FailureKind failureKind,
    Sort sort,
    Integer limit,
    Integer offset
) {
    public record Range(Double min, Double max) { }

    /**
     * Accepts ISO date-times ({@code 2026-07-01T14:30}, space separator also allowed)
     * or plain dates. A plain date keeps its historic day-inclusive semantics:
     * {@code after} expands to the start of the day, {@code before} to the end of it.
     */
    public record DateRange(
        @JsonDeserialize(using = StartDateTimeDeserializer.class) LocalDateTime after,
        @JsonDeserialize(using = EndDateTimeDeserializer.class) LocalDateTime before) { }

    /** Parses a date-time, expanding date-only values to the start or end of the day. */
    abstract static class FlexibleDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {
        private final LocalTime dateOnlyTime;

        FlexibleDateTimeDeserializer(LocalTime dateOnlyTime) {
            this.dateOnlyTime = dateOnlyTime;
        }

        @Override
        public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            String text = parser.getValueAsString();
            if (text == null || text.isBlank()) {
                return null;
            }
            text = text.trim().replace(' ', 'T');
            try {
                return LocalDateTime.parse(text);
            } catch (DateTimeParseException notDateTime) {
                return LocalDate.parse(text).atTime(dateOnlyTime);
            }
        }
    }

    static final class StartDateTimeDeserializer extends FlexibleDateTimeDeserializer {
        StartDateTimeDeserializer() { super(LocalTime.MIN); }
    }

    static final class EndDateTimeDeserializer extends FlexibleDateTimeDeserializer {
        EndDateTimeDeserializer() { super(LocalTime.MAX); }
    }

    public enum Activity { ANY, ACTIVE, DORMANT }

    public enum TagMode { ANY, ALL }

    public enum TableMode { ANY, ALL }

    /** Tri-state matching a boolean characteristic. */
    public enum Flag { ANY, YES, NO }

    public enum FailureKind { ANALYZER, PARSE }

    /** Sort keys; results are always descending (worst first). IMPACT = total query time; the default. */
    public enum Sort { IMPACT, AVG_TIME, MAX_TIME, AVG_ROWS_SCANNED, SELECTIVITY, LAST_SEEN }

    /** An unconstrained search (used when the request body is absent). */
    public static final QuerySearchRequest UNCONSTRAINED = new QuerySearchRequest(
        null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null);
}
