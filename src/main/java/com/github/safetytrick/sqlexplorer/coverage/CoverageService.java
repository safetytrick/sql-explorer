package com.github.safetytrick.sqlexplorer.coverage;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.evidence.SqlImportStats;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepository;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryManager;
import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureArchive;
import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import org.springframework.stereotype.Service;

import static com.github.safetytrick.sqlexplorer.coverage.Coverage.Finding;
import static com.github.safetytrick.sqlexplorer.coverage.Coverage.ObservedWindow;
import static com.github.safetytrick.sqlexplorer.coverage.Coverage.Signal;
import static com.github.safetytrick.sqlexplorer.coverage.Coverage.SourceCoverage;
import static com.github.safetytrick.sqlexplorer.coverage.Coverage.Verdict;

/**
 * Judges each loaded source against what its own capture recorded.
 *
 * <p>Every value read here comes from the capture — the footer written when
 * the rows were read — and never from a live connection. Reading
 * {@code performance_schema_digests_size} off a server today and attaching it
 * to an export taken months ago from possibly another machine produces a
 * confident wrong answer, which is worse than saying nothing because it looks
 * like information (sqlx-sy5, sqlx-n01).
 */
@Service
public class CoverageService {

    /**
     * The value worth setting {@code performance_schema_max_digest_length} to.
     * The default is 1024, and MySQL needs 1028 bytes internally to compute a
     * digest, so at the default a statement barely over a kilobyte is already
     * truncated before its digest is formed.
     */
    static final int RECOMMENDED_MAX_DIGEST_LENGTH = 4096;

    private final SqlRepositoryManager repositories;
    private final CaptureArchive archive;

    public CoverageService(SqlRepositoryManager repositories, CaptureArchive archive) {
        this.repositories = repositories;
        this.archive = archive;
    }

    public Coverage.Report report(String environmentName) {
        SqlRepositoryManager.LoadState state = repositories.getLoadState(environmentName);
        List<SqlRepository> loaded = repositories.getRepositories(environmentName);
        if (state == null || loaded.isEmpty()) {
            return new Coverage.Report(environmentName, false, null, CaptureContext.ORIGIN_TABLE, List.of());
        }

        // Read once for the whole report: the archive is a footer seek per
        // file, and every source would otherwise re-scan the same directory.
        CaptureArchive.ArchiveReport gaps = archive.scan();

        List<SourceCoverage> sources = loaded.stream()
            .map(repository -> describe(repository, gaps))
            .toList();
        return new Coverage.Report(environmentName, true, state.loadedOn(), CaptureContext.ORIGIN_TABLE, sources);
    }

    private SourceCoverage describe(SqlRepository repository, CaptureArchive.ArchiveReport gaps) {
        CaptureContext capture = repository.getCaptureContext();
        SqlSource source = repository.getSource();
        SqlImportStats stats = repository.getImportStats();
        String sourceRef = capture != null && capture.sourceRef() != null ? capture.sourceRef()
            : source == null ? null : source.getSourceRef();
        Map<String, String> server = capture == null || capture.server() == null ? Map.of() : capture.server();

        ObservedWindow window = windowOf(capture);
        List<Finding> findings = new ArrayList<>();
        findings.add(digestCapacity(server, stats));
        findings.add(digestTruncation(server, stats));
        findings.add(observationWindow(window, server));
        findings.add(provenance(capture, source));
        findings.add(samplingGaps(sourceRef, gaps));
        findings.add(archivedHistory(sourceRef, window, gaps));

        return new SourceCoverage(sourceRef,
            capture == null || capture.origin() == null ? null : capture.origin().name(),
            capture == null ? null : capture.capturedAt(),
            stats == null ? 0 : stats.count(),
            window,
            List.copyOf(findings));
    }

    private static ObservedWindow windowOf(CaptureContext capture) {
        if (capture == null || capture.observedFrom() == null || capture.observedTo() == null) {
            return null;
        }
        LocalDateTime from = capture.observedFrom();
        LocalDateTime to = capture.observedTo();
        return new ObservedWindow(from, to, Duration.between(from, to).toDays());
    }

    /**
     * Was the digest table full?
     *
     * <p>{@code Performance_schema_digest_lost} counts digests the server could
     * not record, so it is the direct measurement and is preferred whenever it
     * was captured. Row count against {@code performance_schema_digests_size}
     * is the inference to fall back on.
     *
     * <p>AT CAPACITY THE SERVER STOPS TAKING NEW DIGESTS; it does not evict old
     * ones (Michael, 2026-08-31 — this said "evicted" and was wrong). So the
     * counts already in the table stay accurate and keep accumulating, and what
     * is lost is every query shape that first appeared after the table filled.
     * That is a different claim, and a more actionable one: the numbers you can
     * see are trustworthy, the absences are not.
     */
    private static Finding digestCapacity(Map<String, String> server, SqlImportStats stats) {
        Integer lost = number(server.get("Performance_schema_digest_lost"));
        if (lost != null) {
            return lost > 0
                ? new Finding(Signal.DIGEST_CAPACITY, Verdict.IMPAIRED,
                    lost + " digests lost", "Performance_schema_digest_lost on the source server",
                    "The digest table was full, so " + lost + " statement shapes were never recorded at all — "
                        + "at capacity the server stops taking on new digests rather than evicting old ones. The "
                        + "counts you can see are accurate; what is missing is an unknowable number of distinct "
                        + "queries, and nothing here can say which.")
                : new Finding(Signal.DIGEST_CAPACITY, Verdict.OK,
                    "0 digests lost", "Performance_schema_digest_lost on the source server",
                    "The source server reports that it never ran out of room for a digest, so no statement "
                        + "was dropped for lack of space.");
        }

        Integer size = number(server.get("performance_schema_digests_size"));
        if (size == null || stats == null) {
            return new Finding(Signal.DIGEST_CAPACITY, Verdict.UNKNOWN,
                stats == null ? "unknown" : stats.count() + " profiles", "no capture-time server settings recorded",
                "Nothing recorded how much room the source server had for digests, so whether statements were "
                    + "evicted cannot be judged. It is not safe to assume none were.");
        }

        int count = stats.count();
        String measurement = count + " of " + size + " digests";
        return count >= size
            ? new Finding(Signal.DIGEST_CAPACITY, Verdict.IMPAIRED, measurement,
                "performance_schema_digests_size on the source server",
                "The digest table was at capacity when this was captured. At the cap the server stops recording "
                    + "new query shapes rather than evicting old ones, so these counts are accurate but any query "
                    + "that first ran after the table filled is absent entirely, and how many is unknowable.")
            : new Finding(Signal.DIGEST_CAPACITY, Verdict.OK, measurement,
                "performance_schema_digests_size on the source server",
                "The digest table had room to spare, so no statement was dropped for lack of space.");
    }

    /**
     * Were statements too long to digest in full?
     *
     * <p>Two facts only mean something together: how many profiles came back
     * truncated, and what the server's effective digest limit actually was.
     * The second says whether to expect more.
     *
     * <p>THE EFFECTIVE LIMIT IS THE LOWER OF TWO VARIABLES, and getting this
     * wrong is the difference between advice that works and advice that does
     * nothing. {@code max_digest_length} governs computing the digest;
     * {@code performance_schema_max_digest_length} governs storing it, and
     * Performance Schema cannot store more than was computed. Measured on a
     * stock MySQL 8.4.4: with the pair at (1024, 4096) a 3.6KB statement is
     * still cut at 1024, and only raising BOTH to 4096 retains it. The common
     * advice to raise the performance_schema one alone has no effect.
     */
    private static Finding digestTruncation(Map<String, String> server, SqlImportStats stats) {
        int truncated = stats == null ? 0 : stats.truncated();
        Integer stored = number(server.get("performance_schema_max_digest_length"));
        Integer computed = number(server.get("max_digest_length"));
        String measurement = truncated + " truncated";

        Integer limit = effectiveDigestLimit(stored, computed);
        if (limit == null) {
            return new Finding(Signal.DIGEST_TRUNCATION,
                truncated > 0 ? Verdict.IMPAIRED : Verdict.UNKNOWN,
                measurement, "the source server's digest length limits were not recorded",
                truncated + " statements arrived cut short, and without the server's digest length limits "
                    + "there is no telling whether to expect more. Two statements truncated at the same point "
                    + "share a digest, so they may have been merged into one profile here.");
        }

        String basis = "an effective digest limit of " + limit + " bytes on the source server"
            + " (max_digest_length " + describe(computed) + ", performance_schema_max_digest_length "
            + describe(stored) + "; the lower one binds)";

        if (truncated > 0) {
            String advice = limit < RECOMMENDED_MAX_DIGEST_LENGTH
                ? " Raising BOTH variables to " + RECOMMENDED_MAX_DIGEST_LENGTH + " would capture more of them"
                    + " — raising only one has no effect, because a digest cannot be stored longer than it was"
                    + " computed. Both need a server restart."
                : "";
            return new Finding(Signal.DIGEST_TRUNCATION, Verdict.IMPAIRED, measurement, basis,
                truncated + " statements were longer than the server's effective digest limit of " + limit
                    + " bytes and were cut short before their digest was computed. Two statements truncated at "
                    + "the same point share a digest, so some of these may be several queries merged into one."
                    + advice);
        }
        return limit < RECOMMENDED_MAX_DIGEST_LENGTH
            ? new Finding(Signal.DIGEST_TRUNCATION, Verdict.OK, measurement, basis,
                "No statement was cut short, though the effective limit of " + limit + " bytes is low enough "
                    + "that a longer query would be. " + RECOMMENDED_MAX_DIGEST_LENGTH + " is the value worth "
                    + "setting, on both variables.")
            : new Finding(Signal.DIGEST_TRUNCATION, Verdict.OK, measurement, basis,
                "No statement was long enough to be cut short before its digest was computed.");
    }

    /**
     * The lower of the two limits, or whichever one was recorded.
     *
     * <p>A server that reported only one is judged on that one — it is the
     * only evidence there is, and saying nothing because the other is missing
     * would discard a real measurement.
     */
    private static Integer effectiveDigestLimit(Integer stored, Integer computed) {
        if (stored == null) {
            return computed;
        }
        return computed == null ? stored : Math.min(stored, computed);
    }

    private static String describe(Integer value) {
        return value == null ? "not recorded" : String.valueOf(value);
    }

    /**
     * How long a period do the rows cover?
     *
     * <p>This is the signal that turns "no query touches this table" from a
     * shrug into a claim: unused across 117 days says something, unused across
     * two days says almost nothing. Server uptime corroborates it — Performance
     * Schema is cleared on restart, so an uptime shorter than the observed
     * window means one of the two is wrong.
     */
    private static Finding observationWindow(ObservedWindow window, Map<String, String> server) {
        if (window == null) {
            return new Finding(Signal.OBSERVATION_WINDOW, Verdict.UNKNOWN, "unknown",
                "no statement timestamps were recorded with this capture",
                "How long a period these queries cover is unrecorded, so the absence of a query says nothing "
                    + "about whether it is unused.");
        }

        String measurement = window.days() + " days";
        String span = window.from() + " to " + window.to();
        Integer uptime = number(server.get("Uptime"));
        if (uptime != null && uptime < Duration.between(window.from(), window.to()).toSeconds()) {
            return new Finding(Signal.OBSERVATION_WINDOW, Verdict.IMPAIRED, measurement,
                "statement timestamps " + span + ", but the server reported only " + uptime + "s of uptime",
                "The rows span a longer period than the server had been running, and Performance Schema is "
                    + "cleared on restart — so the timestamps and the uptime disagree and one of them is wrong. "
                    + "Treat the window as unreliable.");
        }
        return new Finding(Signal.OBSERVATION_WINDOW, Verdict.OK, measurement, "statement timestamps " + span,
            "Queries here were observed across " + window.days() + " days. A table no query touched in that "
                + "period is a claim about " + window.days() + " days of traffic — no more, and no less.");
    }

    /**
     * How far from {@code sys.x$statement_analysis} is this, and when was it
     * read? A copy of an export from a server nobody can query any more
     * supports weaker claims than a direct read, and saying so is the point.
     */
    private static Finding provenance(CaptureContext capture, SqlSource source) {
        if (capture == null || capture.origin() == null) {
            return new Finding(Signal.PROVENANCE, Verdict.UNKNOWN,
                source == null ? "unknown" : source.getSourceRef(), "no provenance was recorded",
                "Nothing records where these rows came from or when they were read. They still originate in "
                    + CaptureContext.ORIGIN_TABLE + " — every corpus does — but how many hands they passed "
                    + "through, and how stale they are, is unknown.");
        }

        // No timestamp in the measurement: capturedAt travels as its own field
        // and a view formats it. Rendering an instant into a display string
        // here is how a page ends up showing raw nanoseconds.
        String when = capture.capturedAt() == null ? ", at an unrecorded time" : "";
        return switch (capture.origin()) {
            case PERFORMANCE_SCHEMA -> new Finding(Signal.PROVENANCE, Verdict.OK, "read directly" + when,
                CaptureContext.ORIGIN_TABLE + " on a live server",
                "Read straight off the source server, so the settings judged above are that server's own and "
                    + "describe this data.");
            case CSV_EXPORT -> new Finding(Signal.PROVENANCE, Verdict.OK, "exported once" + when,
                "a CSV export of " + CaptureContext.ORIGIN_TABLE,
                "One hop from the origin table. Everything claimed here rests on what the export recorded "
                    + "about the server it came from; the server itself is not being consulted.");
            case PARQUET_COPY -> new Finding(Signal.PROVENANCE, Verdict.OK, "copied" + when,
                "a Parquet copy sql-explorer wrote earlier",
                "At least two hops from the origin table, and the source server cannot be consulted. The "
                    + "capture-time settings travelled with the file, which is what keeps the judgements "
                    + "above about the right server at the right moment.");
        };
    }

    /**
     * Was the digest table reset between two captures of this source?
     *
     * <p>Per source, because each is its own sampling stream. The archive
     * reports the interval; nothing here can know why it reset, so "unobserved
     * between X and Y" is the whole claim.
     */
    private static Finding samplingGaps(String sourceRef, CaptureArchive.ArchiveReport archive) {
        List<CaptureArchive.CaptureGap> mine = archive.gaps().stream()
            .filter(gap -> gap.sourceRef() != null && gap.sourceRef().equals(sourceRef))
            .toList();

        if (!mine.isEmpty()) {
            String intervals = mine.stream()
                .map(gap -> gap.unobservedFrom() + " to " + gap.unobservedTo())
                .reduce((a, b) -> a + ", " + b).orElse("");
            return new Finding(Signal.SAMPLING_GAPS, Verdict.IMPAIRED,
                mine.size() + (mine.size() == 1 ? " gap" : " gaps"), "unobserved: " + intervals,
                "Between two captures of this source the digest table was reset — a server restart, or a "
                    + "TRUNCATE — and traffic in those intervals was never recorded by anything.");
        }
        String failures = archive.failed().isEmpty() ? ""
            : " " + archive.failed().size() + (archive.failed().size() == 1 ? " capture" : " captures")
                + " did not finish writing and hold nothing readable; that costs those samples, but not this "
                + "answer — the completed captures on either side are still compared with each other.";

        if (archive.unorderable().isEmpty()) {
            return new Finding(Signal.SAMPLING_GAPS, Verdict.OK, "no gaps",
                "consecutive captures of this source overlap",
                "Each capture of this source still contained what the one before it saw, which is what an "
                    + "uninterrupted Performance Schema looks like. Nothing went unobserved between them."
                    + failures);
        }
        int unplaceable = archive.unorderable().size();
        return new Finding(Signal.SAMPLING_GAPS, Verdict.UNKNOWN, "no gaps found",
            unplaceable + (unplaceable == 1 ? " capture" : " captures") + " could not be placed in order",
            "No gap was found among the captures that could be ordered, but " + unplaceable + " could not be "
                + "placed — they record no capture time — so they cover some unknown stretch and a reset "
                + "beside them would not show up here." + failures);
    }

    /**
     * Does the archive hold periods this corpus does not cover?
     *
     * <p>The corpus is one capture — the newest — because consecutive captures
     * of a source overlap and summing them would double-count. When the digest
     * table is reset, though, the captures either side describe DISJOINT
     * periods, and everything before the reset is on disk and unread. This says
     * so, because "118 days observed" is a different claim depending on whether
     * it is all the evidence there is or the most recent slice of it (sqlx-65m).
     *
     * <p>NOT A WARNING. A source with one run says so plainly, the same way
     * DIGEST_CAPACITY reports OK rather than only ever complaining — a signal
     * that speaks up only for problems is one people stop reading.
     */
    private static Finding archivedHistory(String sourceRef, ObservedWindow window,
                                           CaptureArchive.ArchiveReport archive) {
        List<CaptureArchive.CaptureRun> runs = archive.runsOf(sourceRef);
        if (runs.isEmpty()) {
            return new Finding(Signal.ARCHIVED_HISTORY, Verdict.UNKNOWN, "no captures on disk",
                "nothing to compare this corpus against",
                "No archived capture describes this source, so whether it once covered a longer period "
                    + "cannot be told from here. A direct read that has never been written down looks "
                    + "exactly like one whose history was lost.");
        }
        if (runs.size() == 1) {
            return new Finding(Signal.ARCHIVED_HISTORY, Verdict.OK, "one unbroken period",
                runs.getFirst().captures() + " captures, no reset between them",
                "Every capture of this source belongs to one uninterrupted stretch, so what is loaded is "
                    + "all the evidence there is. Nothing earlier is sitting on disk unread.");
        }

        LocalDateTime earliest = runs.stream().map(CaptureArchive.CaptureRun::observedFrom)
            .filter(java.util.Objects::nonNull).min(LocalDateTime::compareTo).orElse(null);
        LocalDateTime latest = runs.stream().map(CaptureArchive.CaptureRun::observedTo)
            .filter(java.util.Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
        String span = earliest == null || latest == null ? "an unrecorded period"
            : Duration.between(earliest, latest).toDays() + " days, " + earliest + " to " + latest;
        String loaded = window == null ? "an unrecorded period" : window.days() + " days";

        return new Finding(Signal.ARCHIVED_HISTORY, Verdict.IMPAIRED,
            (runs.size() - 1) + (runs.size() == 2 ? " reset" : " resets"),
            "archive spans " + span + "; this corpus covers " + loaded,
            "The digest table was reset " + (runs.size() - 1) + " time(s) across this source's captures, so "
                + "the archive holds " + (runs.size() - 1) + " earlier period(s) that the loaded corpus does "
                + "not cover. Those captures are on disk and describe intervals disjoint from this one — "
                + "they are not loaded, because within a run captures overlap and summing them would "
                + "double-count every query.");
    }

    private static Integer number(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
