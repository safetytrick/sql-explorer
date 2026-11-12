package com.github.safetytrick.sqlexplorer.evidence;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetConfig;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rebuilding a source from its own capture. The failures that matter are the
 * quiet ones: serving a corpus that merges along an old normalizer's lines, or
 * reporting a clean bill of health because the recounted statistics came back
 * zero (sqlx-4ww).
 */
class CaptureRehydratorTest {

    @TempDir
    Path directory;

    private static final String CURRENT = "v" + com.github.safetytrick.sqlexplorer.analysis.parser
        .QueryNormalizer.VERSION + ":";

    private ParquetConfig config() {
        ParquetConfig config = new ParquetConfig();
        config.setFilename(directory.resolve("sql-repository.parquet").toString());
        return config;
    }

    private static RuntimeMetrics metrics(String digest, long executed) {
        return RuntimeMetrics.newBuilder()
            .setFullTableScan(false).setExecuted(executed).setErrors(0).setWarnings(0)
            .setTotalTime(BigDecimal.TEN).setMaxTime(BigDecimal.ONE).setAverageTime(BigDecimal.ONE)
            .setRowsSent(1).setAverageRowsSent(1).setRowsScanned(1).setAverageRowsScanned(1)
            .setTempTables(0).setTempDiskTables(0).setRowsSorted(0).setSortMergePasses(0)
            .setDigest(digest).setLastSeen(LocalDateTime.parse("2026-08-30T12:00:00"))
            .setFirstSeen(LocalDateTime.parse("2026-05-04T12:00:00"))
            .setLockTime(BigDecimal.ZERO).setRowsAffected(0).setAverageRowsAffected(0)
            .completeBuild();
    }

    private static SqlProfile profile(String normalizedDigest, String perfDigest, long executed,
                                      boolean truncated) {
        return SqlProfile.newBuilder()
            .setQuery("select ? from t").setType("SELECT")
            .setDigest(normalizedDigest).setNormalizedDigest(normalizedDigest)
            .setSchemaName("app").setQueryActivityStatus(QueryActivityStatus.ACTIVE)
            .setTruncated(truncated)
            .setRuntimeMetrics(metrics(perfDigest, executed))
            .completeBuild("app");
    }

    private static CaptureContext capture() {
        return new CaptureContext(CaptureContext.Origin.PERFORMANCE_SCHEMA, "primary",
            Instant.parse("2026-08-30T18:33:14Z"), 3,
            LocalDateTime.parse("2026-05-04T12:00:00"), LocalDateTime.parse("2026-08-30T12:00:00"),
            Map.of("version", "8.4.8", "performance_schema_digests_size", "10000"));
    }

    private CaptureRehydrator rehydratorOver(List<SqlProfile> rows) {
        ParquetConfig config = config();
        ParquetSink sink = new ParquetSink(config);
        sink.write(capture(), rows);
        return new CaptureRehydrator(config, sink);
    }

    /**
     * The point of the whole exercise: the group is rebuilt, so the corpus
     * reports the group's summed executions rather than one member's.
     */
    @Test
    void aRehydratedCorpusRebuildsItsMergeGroups() {
        SqlRepository repository = rehydratorOver(List.of(
            profile(CURRENT + "aaa", "perf-1", 100L, false),
            profile(CURRENT + "aaa", "perf-2", 12L, false),
            profile(CURRENT + "bbb", "perf-3", 5L, false))).rehydrate("primary");

        assertThat(repository).isNotNull();
        assertThat(repository.getAllAnalyzedSql().count()).isEqualTo(2);
        assertThat(repository.getAllAnalyzedSql()
            .filter(p -> p.getNormalizedDigest().endsWith("aaa"))
            .findFirst().orElseThrow().getRuntimeMetrics().getExecuted()).isEqualTo(112L);
    }

    /**
     * A rehydrated source describes itself from the footer, so /coverage judges
     * it against the server it actually came from rather than a live one.
     */
    @Test
    void theRehydratedSourceCarriesTheCapturesOwnProvenance() {
        SqlRepository repository = rehydratorOver(List.of(profile(CURRENT + "aaa", "p1", 1L, false)))
            .rehydrate("primary");

        assertThat(repository.getSource().getSourceRef()).isEqualTo("primary");
        assertThat(repository.getSource().getCapturedAt()).isEqualTo(Instant.parse("2026-08-30T18:33:14Z"));
        assertThat(repository.getSource().getCaptureServerContext()).containsEntry("version", "8.4.8");
        assertThat(repository.getCaptureContext().observedFrom())
            .isEqualTo(LocalDateTime.parse("2026-05-04T12:00:00"));
    }

    /**
     * Re-reading our own file adds no hop to the chain back to
     * sys.x$statement_analysis. Restamping it PARQUET_COPY would make each
     * rehydration look one step more distant than the last.
     */
    @Test
    void rehydrationDoesNotDemoteTheOrigin() {
        SqlRepository repository = rehydratorOver(List.of(profile(CURRENT + "aaa", "p1", 1L, false)))
            .rehydrate("primary");

        assertThat(repository.getSource().getCaptureOrigin())
            .isEqualTo(CaptureContext.Origin.PERFORMANCE_SCHEMA);
    }

    /**
     * The statistics are not in the file and are recounted from the rows.
     * /coverage reads truncated and count from here — zeros would read as a
     * clean bill of health nothing had earned.
     */
    @Test
    void importStatsAreRecountedFromTheRows() {
        SqlRepository repository = rehydratorOver(List.of(
            profile(CURRENT + "aaa", "perf-1", 100L, true),
            profile(CURRENT + "aaa", "perf-2", 12L, false),
            profile(CURRENT + "bbb", "perf-3", 5L, true))).rehydrate("primary");

        SqlImportStats stats = repository.getImportStats();
        assertThat(stats.count()).isEqualTo(3);
        assertThat(stats.truncated()).isEqualTo(2);
        assertThat(stats.merged()).as("three rows, two distinct digests").isEqualTo(1);
    }

    /**
     * A capture written by a different normalizer groups queries along the old
     * scheme's lines. Serving it would merge one environment by one rule while
     * everything else in the process uses another — so it is refused, and the
     * caller imports instead.
     */
    @Test
    void aCaptureFromAnotherNormalizerIsRefused() {
        assertThat(rehydratorOver(List.of(
            profile("v1:old-scheme-digest", "perf-1", 100L, false))).rehydrate("primary")).isNull();
    }

    /** A source with no capture cannot be rehydrated, and does not pretend to be. */
    @Test
    void aSourceWithNoCaptureReturnsNothingRatherThanAnEmptyCorpus() throws IOException {
        ParquetConfig config = config();
        CaptureRehydrator rehydrator = new CaptureRehydrator(config, new ParquetSink(config));

        assertThat(rehydrator.rehydrate("never-captured")).isNull();
    }

    /** Another source's capture is never served in its place. */
    @Test
    void oneSourceNeverRehydratesFromAnothersCapture() {
        CaptureRehydrator rehydrator =
            rehydratorOver(List.of(profile(CURRENT + "aaa", "p1", 1L, false)));

        assertThat(rehydrator.rehydrate("primary")).isNotNull();
        assertThat(rehydrator.rehydrate("replica")).isNull();
    }

    /** A capture from before profiles named their source is labelled with its own (sqlx-a9h.1). */
    @Test
    void rowsWithoutASourceAreLabelledWithTheCaptures() {
        SqlProfile old = new SqlProfile();
        old.setSources(List.of());
        SqlProfile named = new SqlProfile();
        named.setSources(List.of("shard2"));

        CaptureRehydrator.labelSources(List.of(old, named), "shard1");

        assertThat(old.getSources()).containsExactly("shard1");
        assertThat(named.getSources()).as("a row that names its source keeps it").containsExactly("shard2");
    }
}
