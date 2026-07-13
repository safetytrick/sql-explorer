package com.github.safetytrick.sqlexplorer.profiles;

import java.time.LocalDate;
import java.util.List;

/**
 * Filter/sort/page criteria for {@code POST /queries/search}. Every field is
 * optional; a missing field does not constrain the results. Times are accepted
 * in milliseconds (metrics are stored in picoseconds), selectivity as a 0..1
 * fraction (rows sent / rows scanned).
 */
public record QuerySearchRequest(
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

    public record DateRange(LocalDate after, LocalDate before) { }

    public enum Activity { ANY, ACTIVE, DORMANT }

    public enum TagMode { ANY, ALL }

    /** Tri-state matching a boolean characteristic. */
    public enum Flag { ANY, YES, NO }

    public enum FailureKind { ANALYZER, PARSE }

    /** Sort keys; results are always descending (worst first). IMPACT = total query time; the default. */
    public enum Sort { IMPACT, AVG_TIME, MAX_TIME, AVG_ROWS_SCANNED, SELECTIVITY, LAST_SEEN }

    /** An unconstrained search (used when the request body is absent). */
    public static final QuerySearchRequest UNCONSTRAINED = new QuerySearchRequest(
        null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null);
}
