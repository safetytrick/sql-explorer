package com.github.safetytrick.sqlexplorer.evidence;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;
import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureArchive;
import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetConfig;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the archive still knows after a reset erased it from the corpus.
 *
 * <p>The mistake these guard against is a report presenting an artifact of the
 * capture as a fact about the schema: Performance Schema is cleared on reset, so
 * a table used by a monthly job is absent from a corpus taken a week later and
 * reads as one nothing has ever touched (sqlx-qwn).
 */
class ArchivedActivityTest {

    @TempDir
    Path directory;

    private ParquetConfig config;

    private ParquetConfig config() {
        if (config == null) {
            config = new ParquetConfig();
            config.setFilename(directory.resolve("sql-repository.parquet").toString());
        }
        return config;
    }

    /** One capture of one source, holding whatever profiles are named. */
    private void capture(String sourceRef, String capturedAt, String observedFrom, String observedTo,
                         SqlProfile... profiles) {
        new ParquetSink(config()).write(new CaptureContext(
            CaptureContext.Origin.PERFORMANCE_SCHEMA, sourceRef,
            Instant.parse(capturedAt), profiles.length,
            LocalDateTime.parse(observedFrom), LocalDateTime.parse(observedTo),
            Map.of()), List.of(profiles));
    }

    private static SqlProfile touching(String schema, String table, String lastSeen) {
        return SqlProfile.newBuilder()
            .setQuery("select * from " + table).setType("SELECT")
            .setDigest(table + lastSeen).setNormalizedDigest(table + lastSeen)
            .setSchemaName(schema)
            .setQueryActivityStatus(QueryActivityStatus.ACTIVE)
            .setTables(List.of(TableReference.newBuilder()
                .setSchemaName(schema).setTableName(table)
                .setNormalizedSchemaName(schema).setNormalizedTableName(table)
                .build()))
            .setRuntimeMetrics(metrics(lastSeen))
            .completeBuild(schema);
    }

    /**
     * Only {@code lastSeen} carries meaning here; the rest are the zeroes Avro
     * requires, since none of them has a schema default.
     */
    private static RuntimeMetrics metrics(String lastSeen) {
        return RuntimeMetrics.newBuilder()
            .setFullTableScan(false)
            .setExecuted(1).setErrors(0).setWarnings(0)
            .setTotalTime(java.math.BigDecimal.ONE)
            .setMaxTime(java.math.BigDecimal.ONE)
            .setAverageTime(java.math.BigDecimal.ONE)
            .setRowsSent(0).setAverageRowsSent(0)
            .setRowsScanned(0).setAverageRowsScanned(0)
            .setTempTables(0).setTempDiskTables(0)
            .setRowsSorted(0).setSortMergePasses(0)
            .setDigest("d")
            .setLastSeen(LocalDateTime.parse(lastSeen))
            .setFirstSeen(LocalDateTime.parse(lastSeen))
            .setLockTime(java.math.BigDecimal.ZERO)
            .setRowsAffected(0).setAverageRowsAffected(0)
            .build();
    }

    private ArchivedActivity activity(String... sourceRefs) {
        SqlRepositoryManager repositories = mock(SqlRepositoryManager.class);
        when(repositories.sourceRefs("prod")).thenReturn(List.of(sourceRefs));
        return new ArchivedActivity(config(), new CaptureArchive(config()),
            new ParquetSink(config()), repositories, mock(EnvironmentContext.class));
    }

    /**
     * THE CASE THE WHOLE THING EXISTS FOR. A table queried before the reset and
     * not since is absent from the corpus entirely; only the earlier capture
     * records that it was ever used, and when.
     */
    @Test
    void aTableQueriedOnlyBeforeAResetIsStillKnown() {
        capture("primary", "2026-06-01T00:00:00Z", "2026-01-01T00:00:00", "2026-06-01T00:00:00",
            touching("app", "quarterly_rollup", "2026-05-30T02:00:00"));
        capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00",
            touching("app", "orders", "2026-07-31T02:00:00"));

        assertThat(activity("primary").lastTouchedBeforeThisRun("prod"))
            .containsEntry("app.quarterly_rollup", LocalDateTime.parse("2026-05-30T02:00:00"));
    }

    /**
     * The ordinary case, and the one that must stay free: with no reset there is
     * no earlier run, so there is nothing to read and no file is opened.
     */
    @Test
    void withoutAResetTheArchiveIsNotConsulted() {
        capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00",
            touching("app", "orders", "2026-07-20T02:00:00"));
        capture("primary", "2026-08-02T00:00:00Z", "2026-07-01T00:00:00", "2026-08-02T00:00:00",
            touching("app", "orders", "2026-08-01T02:00:00"));

        assertThat(activity("primary").lastTouchedBeforeThisRun("prod")).isEmpty();
    }

    /**
     * Within a run only the last capture is read — it accumulated what the
     * earlier ones saw, so reading them too would be redundant work that can
     * only produce an older answer for the same table.
     */
    @Test
    void theLastCaptureOfAnEarlierRunIsTheOneRead() {
        capture("primary", "2026-06-01T00:00:00Z", "2026-01-01T00:00:00", "2026-06-01T00:00:00",
            touching("app", "rollup", "2026-03-01T02:00:00"));
        capture("primary", "2026-06-20T00:00:00Z", "2026-01-01T00:00:00", "2026-06-20T00:00:00",
            touching("app", "rollup", "2026-06-15T02:00:00"));
        capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00",
            touching("app", "orders", "2026-07-31T02:00:00"));

        assertThat(activity("primary").lastTouchedBeforeThisRun("prod"))
            .containsEntry("app.rollup", LocalDateTime.parse("2026-06-15T02:00:00"));
    }

    /** Across two resets, the newest use wins — a table may appear in both. */
    @Test
    void theNewestUseAcrossEveryEarlierRunIsKept() {
        capture("primary", "2026-03-01T00:00:00Z", "2026-01-01T00:00:00", "2026-03-01T00:00:00",
            touching("app", "rollup", "2026-02-01T02:00:00"));
        capture("primary", "2026-06-01T00:00:00Z", "2026-04-01T00:00:00", "2026-06-01T00:00:00",
            touching("app", "rollup", "2026-05-01T02:00:00"));
        capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00",
            touching("app", "orders", "2026-07-31T02:00:00"));

        assertThat(activity("primary").lastTouchedBeforeThisRun("prod"))
            .containsEntry("app.rollup", LocalDateTime.parse("2026-05-01T02:00:00"));
    }

    /**
     * The archive directory holds every environment's captures side by side, so
     * without this an environment would be handed another's history. The
     * coverage cache shipped with exactly this bug once.
     */
    @Test
    void anotherSourcesHistoryIsNotThisEnvironmentsHistory() {
        capture("staging", "2026-06-01T00:00:00Z", "2026-01-01T00:00:00", "2026-06-01T00:00:00",
            touching("app", "someone_elses", "2026-05-30T02:00:00"));
        capture("staging", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00",
            touching("app", "orders", "2026-07-31T02:00:00"));

        assertThat(activity("primary").lastTouchedBeforeThisRun("prod")).isEmpty();
    }

    /** A profile whose metrics never recorded a last-seen contributes nothing. */
    @Test
    void aProfileWithNoLastSeenIsSkipped() {
        SqlProfile undated = SqlProfile.newBuilder()
            .setQuery("select 1").setType("SELECT")
            .setDigest("d").setNormalizedDigest("d").setSchemaName("app")
            .setQueryActivityStatus(QueryActivityStatus.ACTIVE)
            .setTables(List.of(TableReference.newBuilder()
                .setSchemaName("app").setTableName("undated")
                .setNormalizedSchemaName("app").setNormalizedTableName("undated")
                .build()))
            .setRuntimeMetrics(null).completeBuild("app");
        capture("primary", "2026-06-01T00:00:00Z", "2026-01-01T00:00:00", "2026-06-01T00:00:00", undated);
        capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00",
            touching("app", "orders", "2026-07-31T02:00:00"));

        assertThat(activity("primary").lastTouchedBeforeThisRun("prod")).isEmpty();
    }
}
