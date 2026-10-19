package com.github.safetytrick.sqlexplorer.coverage;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * What sql-explorer can honestly claim about how much of an environment it
 * actually saw.
 *
 * <p>THE MEASUREMENT AND ITS MEANING TRAVEL TOGETHER. "digests: 4233" means
 * nothing to a reader who does not know Performance Schema, and the
 * explanation without the number is a manual page. Each finding therefore
 * carries the value, what it was judged against, a verdict, and the sentence
 * that says what it implies — so a consumer renders it without knowing any
 * MySQL internals, and the words cannot drift away from the number they
 * describe. That drift is exactly what went wrong with the hedge this replaces
 * (sqlx-n01).
 *
 * <p>DELIBERATELY NOT A SINGLE PERCENTAGE. These signals fail in unrelated
 * ways — an evicted digest table, a truncated statement, a short observation
 * window, a reset between samples — and averaging them yields a figure nobody
 * can act on.
 *
 * <p>PER SOURCE, because that is the only level at which the numbers are true.
 * Two sources in one environment may read different servers with different
 * settings, and a single environment-wide answer would describe neither.
 */
public final class Coverage {

    private Coverage() {
    }

    /** What a finding says about the corpus. */
    public enum Verdict {
        /** Measured, and it does not limit what the corpus can be used for. */
        OK,
        /** Measured, and the corpus is known to be missing something. */
        IMPAIRED,
        /**
         * Not measurable from what was recorded. Distinct from OK on purpose:
         * an unknown is not a pass, and reporting it as zero would be the
         * confident wrong answer this whole design exists to avoid.
         */
        UNKNOWN
    }

    /** The distinct questions asked of a source. */
    public enum Signal {
        /** Was the digest table full, so that statements were evicted? */
        DIGEST_CAPACITY,
        /** Were statements too long to be digested in full? */
        DIGEST_TRUNCATION,
        /** How long a period do the rows actually cover? */
        OBSERVATION_WINDOW,
        /** How many hops from {@code sys.x$statement_analysis} is this? */
        PROVENANCE,
        /** Was the digest table reset between two captures of this source? */
        SAMPLING_GAPS,
        /** Does the archive hold periods the loaded corpus does not cover? */
        ARCHIVED_HISTORY,
        /** Could the source be read at all? Only ever reported for one that could not (sqlx-jdf). */
        UNREACHABLE,
        /** Was every statement analysed, or left waiting for a schema confirmed current (sqlx-sqy.5)? */
        PENDING_ANALYSIS
    }

    /**
     * One judged measurement.
     *
     * @param signal      which question this answers
     * @param verdict     what it means for use of the corpus
     * @param measurement what was observed, already rendered for a reader
     * @param basis       what it was judged against — the limit, the setting,
     *                    or the reason it could not be judged
     * @param explanation what the reader should conclude, in a sentence
     */
    public record Finding(Signal signal, Verdict verdict, String measurement, String basis, String explanation) {
    }

    /**
     * The observed window, kept zone-less because that is what Performance
     * Schema reports; inventing a zone here would be a guess.
     *
     * @param days whole days spanned, for the common "a schema unused for N
     *             days" phrasing that is the point of knowing the window
     */
    public record ObservedWindow(LocalDateTime from, LocalDateTime to, long days) {
    }

    /**
     * @param sourceRef  which source this describes
     * @param origin     how far it sits from the origin table, or null
     * @param capturedAt when its rows were read, or null when unrecorded
     * @param profiles   how many profiles it contributed
     * @param window     what period those profiles span, or null
     * @param findings   the judged signals, in a stable order
     */
    public record SourceCoverage(String sourceRef, String origin, Instant capturedAt, int profiles,
                                 ObservedWindow window, List<Finding> findings) {
    }

    /**
     * @param environment which environment was asked about
     * @param loaded      whether anything has been loaded into it
     * @param loadedOn    when it was loaded, or null
     * @param originTable the table every corpus ultimately comes from, stated
     *                    once so a consumer need not hard-code it
     * @param sources     one entry per source, never merged
     * @param clusters    where each cluster's schema was read from — its first
     *                    choice, a fallback, or nowhere (sqlx-sqy.2). A
     *                    source's cluster is the one listing its server.
     * @param drift       tables that clusters carrying the same schema define
     *                    differently; lookups use the first cluster's (sqlx-sqy.7)
     */
    public record Report(String environment, boolean loaded, Instant loadedOn, String originTable,
                         List<SourceCoverage> sources,
                         List<com.github.safetytrick.sqlexplorer.environment.Environment.ClusterSchemaStatus> clusters,
                         List<com.github.safetytrick.sqlexplorer.environment.Environment.TableDrift> drift) {
    }
}
