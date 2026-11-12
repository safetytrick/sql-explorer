package com.github.safetytrick.sqlexplorer.evidence;

import java.nio.file.Path;
import java.util.List;

import com.github.safetytrick.sqlexplorer.analysis.parser.QueryNormalizer;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureArchive;
import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetConfig;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetSink;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetSqlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Rebuilds one source's repository from its newest capture, instead of
 * importing it again.
 *
 * <p>WHAT THIS IS FOR, and what it is not. A fresh import of the regression
 * corpus costs about twenty seconds of parsing and analysis; reading the same
 * profiles back costs about a tenth of a second. That difference is worth
 * having when an environment has been evicted from heap and is wanted again —
 * the capture was written by this same process from a live read, so it is
 * exactly what heap held.
 *
 * <p>It is NOT a substitute for the startup read, but it now goes first.
 * Performance Schema accumulates until the server resets, so a live read is
 * always at least as complete as any capture taken earlier. sqlx-4ww kept
 * startup live for that reason, at the cost of answering nothing until every
 * server had been read — minutes, once a production environment had 19 of
 * them. Startup now serves captures at once and runs the live read behind them
 * (sqlx-sqy.6): the window is narrowed only until that read lands, and each
 * source's {@code capturedAt} on {@code /coverage} says how old it is meanwhile.
 *
 * <p>ONE CAPTURE, NEVER SEVERAL. Consecutive captures of a source are
 * overlapping snapshots of cumulative counters, so reading two and merging
 * would double-count every query in the overlap. See
 * {@link ParquetSqlRepository#latestCaptureOf}, and sqlx-65m for the one case
 * where summing is valid.
 */
@Service
public class CaptureRehydrator {

    private static final Logger log = LoggerFactory.getLogger(CaptureRehydrator.class);

    private final ParquetConfig config;
    private final ParquetSink sink;

    public CaptureRehydrator(ParquetConfig config, ParquetSink sink) {
        this.config = config;
        this.sink = sink;
    }

    /**
     * The repository for {@code sourceRef}, or null when it cannot be rebuilt
     * — no capture, an unreadable one, or one written by a different
     * normalizer.
     *
     * <p>Null rather than an empty repository, deliberately: the caller's only
     * correct response is to import the source properly, and an empty corpus
     * presented as a rehydrated one would read as "this environment has no
     * queries".
     */
    public SqlRepository rehydrate(String sourceRef) {
        Path capture = ParquetSqlRepository.latestCaptureOf(config.getFilename(), sourceRef);
        if (capture == null) {
            log.info("no capture for source {}; it has to be imported", sourceRef);
            return null;
        }
        CaptureContext context = CaptureArchive.provenanceOf(capture);
        List<SqlProfile> rows;
        try {
            rows = sink.readAll(capture);
        } catch (Exception e) {
            log.warn("could not read capture {}: {}", capture.getFileName(), e.getMessage());
            return null;
        }
        if (!normalizerMatches(rows)) {
            return null;
        }

        labelSources(rows, context != null && context.sourceRef() != null ? context.sourceRef() : sourceRef);

        log.info("Rehydrated {} from {}: {} profiles", sourceRef, capture.getFileName(), rows.size());
        // Merging is rebuilt here, by AnalyzedSqlRepository grouping on the
        // normalized digest — exactly as a fresh import does. Nothing about the
        // grouping was stored.
        return new AnalyzedSqlRepository(new CapturedSqlSource(capture, context), statsOf(rows), rows, context);
    }

    /**
     * Earlier analysis from the latest capture of each source, for a load to
     * reuse (sqlx-sqy.5). Read one capture at a time into the index, never all
     * at once. A source with no readable capture contributes nothing; its rows
     * are analysed as new.
     */
    public AnalysisReuse reuseFor(List<String> sourceRefs) {
        long start = System.nanoTime();
        AnalysisReuse.Builder index = new AnalysisReuse.Builder();
        int captures = 0;
        for (String sourceRef : sourceRefs) {
            Path capture = sourceRef == null ? null : ParquetSqlRepository.latestCaptureOf(config.getFilename(), sourceRef);
            if (capture == null) {
                continue;
            }
            try {
                index.addAll(sink.readAll(capture));
                captures++;
            } catch (Exception e) {
                log.warn("could not read capture {} for analysis reuse: {}", capture.getFileName(), e.getMessage());
            }
        }
        AnalysisReuse reuse = index.build();
        log.info("Analysis reuse: {} statements from {} capture(s) in {}", reuse.size(), captures,
            java.time.Duration.ofNanos(System.nanoTime() - start));
        return reuse;
    }

    /**
     * Whether this source could be rebuilt without touching its server.
     *
     * <p>What eviction has to ask before dropping anything. Evicting a source
     * with no usable capture trades bounded memory for an environment that can
     * only come back by re-importing — which needs the analysed server to be
     * reachable, and it may not be. Cheap: a directory listing and a footer
     * seek, at eviction time only (sqlx-4ww).
     */
    public boolean canRehydrate(String sourceRef) {
        return ParquetSqlRepository.latestCaptureOf(config.getFilename(), sourceRef) != null;
    }

    /**
     * Whether the file's digests were produced by the normalizer running now.
     *
     * <p>Digests are {@code v<N>:<sha256>} since sqlx-2oq precisely so that a
     * stale file is detectable rather than silently wrong. If the scheme has
     * moved, the stored digests no longer group the same queries together, and
     * a rehydrated corpus would merge along the old scheme's lines while
     * everything else in the process merges along the new one. Re-importing is
     * the honest answer; re-normalizing in place is possible but is
     * {@code docs/plans/normalization-versioning.md}'s problem, not this
     * method's.
     */
    /**
     * Captures written before profiles recorded their source hold none; the
     * capture knows whose it is, so its rows are labelled with that
     * (sqlx-a9h.1). A row that already names its source keeps it.
     */
    static void labelSources(List<SqlProfile> rows, String sourceRef) {
        if (sourceRef == null) {
            return;
        }
        for (SqlProfile row : rows) {
            if (row.getSources() == null || row.getSources().isEmpty()) {
                row.setSources(List.of(sourceRef));
            }
        }
    }

    private static boolean normalizerMatches(List<SqlProfile> rows) {
        String expected = "v" + QueryNormalizer.VERSION + ":";
        String stale = rows.stream()
            .map(SqlProfile::getNormalizedDigest)
            .filter(digest -> digest != null && !digest.startsWith(expected))
            .findFirst().orElse(null);
        if (stale != null) {
            log.info("capture was normalized by a different scheme ({} vs {}); importing instead",
                stale.substring(0, Math.min(stale.indexOf(':') + 1, stale.length())), expected);
            return false;
        }
        return true;
    }

    /**
     * The import statistics, recounted from the rows.
     *
     * <p>They are not in the file, and they need not be: every field they
     * summarise is on the profile itself. Recounting keeps one source of truth
     * for the same reason merging is rebuilt rather than stored — and
     * {@code /coverage} reads {@code truncated} and {@code count} from here, so
     * a rehydrated environment that reported zeros would look like a clean bill
     * of health it had not earned.
     *
     * <p>{@code merged} counts profiles that folded into another; the rows hold
     * every member, so it is the difference between the row count and the
     * number of distinct normalized digests.
     *
     * <p>{@code notMerged} is the one field that cannot be recovered, and it
     * comes back zero. It counts merge attempts that found nothing to merge
     * with — a fact about the import process rather than about the data, and
     * nothing on a profile records it. Verified against the live corpus: every
     * other field matches a fresh import exactly (count 4311, merged 68,
     * truncated 9, dml 589, analysisFailed 11, analysisSkipped 1836). Nothing
     * reads notMerged today; if something starts to, it has to be told that a
     * rehydrated environment does not have it.
     */
    static SqlImportStats statsOf(List<SqlProfile> rows) {
        SqlImportStats.Builder stats = SqlImportStats.builder();
        long distinct = rows.stream().map(SqlProfile::getNormalizedDigest).distinct().count();
        for (SqlProfile row : rows) {
            stats.count();
            if (Boolean.TRUE.equals(row.getTruncated())) {
                stats.truncated();
            }
            if (Boolean.TRUE.equals(row.getAnalysisFailed())) {
                stats.analysisFailed();
            }
            if (Boolean.TRUE.equals(row.getAnalysisSkipped())) {
                stats.analysisSkipped();
            }
            if (Boolean.TRUE.equals(row.getIsDml())) {
                stats.dml();
            }
        }
        for (long i = 0; i < rows.size() - distinct; i++) {
            stats.merged();
        }
        return stats.build();
    }
}
