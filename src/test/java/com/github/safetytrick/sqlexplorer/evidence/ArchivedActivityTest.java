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

    @Test
    void aTableQueriedOnlyBeforeAResetIsStillKnown() {
        capture("primary", "2026-06-01T00:00:00Z", "2026-01-01T00:00:00", "2026-06-01T00:00:00",
            touching("app", "quarterly_rollup", "2026-05-30T02:00:00"));
        capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00",
            touching("app", "orders", "2026-07-31T02:00:00"));

        assertThat(activity("primary").lastTouchedBeforeThisRun("prod"))
            .containsEntry("app.quarterly_rollup", LocalDateTime.parse("2026-05-30T02:00:00"));
    }

    @Test
    void withoutAResetTheArchiveIsNotConsulted() {
        capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00",
            touching("app", "orders", "2026-07-20T02:00:00"));
        capture("primary", "2026-08-02T00:00:00Z", "2026-07-01T00:00:00", "2026-08-02T00:00:00",
            touching("app", "orders", "2026-08-01T02:00:00"));

        assertThat(activity("primary").lastTouchedBeforeThisRun("prod")).isEmpty();
    }

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

    @Test
    void anotherSourcesHistoryIsNotThisEnvironmentsHistory() {
        capture("staging", "2026-06-01T00:00:00Z", "2026-01-01T00:00:00", "2026-06-01T00:00:00",
            touching("app", "someone_elses", "2026-05-30T02:00:00"));
        capture("staging", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00",
            touching("app", "orders", "2026-07-31T02:00:00"));

        assertThat(activity("primary").lastTouchedBeforeThisRun("prod")).isEmpty();
    }

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
