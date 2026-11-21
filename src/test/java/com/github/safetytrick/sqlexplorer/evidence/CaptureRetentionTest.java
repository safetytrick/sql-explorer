package com.github.safetytrick.sqlexplorer.evidence;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureArchive;
import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetConfig;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetSink;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetSqlRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class CaptureRetentionTest {

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

    private void capture(String sourceRef, String capturedAt, String observedFrom, String observedTo) {
        new ParquetSink(config()).write(new CaptureContext(
            CaptureContext.Origin.PERFORMANCE_SCHEMA, sourceRef,
            Instant.parse(capturedAt), 1,
            observedFrom == null ? null : LocalDateTime.parse(observedFrom),
            observedTo == null ? null : LocalDateTime.parse(observedTo),
            Map.of()), List.of(profile()));
    }

    private static SqlProfile profile() {
        return SqlProfile.newBuilder()
            .setQuery("select 1").setType("SELECT")
            .setDigest("d").setNormalizedDigest("d").setSchemaName("app")
            .setQueryActivityStatus(QueryActivityStatus.ACTIVE)
            .setRuntimeMetrics(null).completeBuild("app");
    }

    private CaptureRetention retention(boolean enabled) {
        return new CaptureRetention(config(), new CaptureArchive(config()), enabled);
    }

    private List<String> onDisk() {
        return ParquetSqlRepository.captures(config().getFilename()).stream()
            .map(path -> String.valueOf(path.getFileName())).toList();
    }

    @Test
    void withinARunOnlyTheNewestSurvives() throws IOException {
        capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00");
        capture("primary", "2026-08-02T00:00:00Z", "2026-07-01T00:00:00", "2026-08-02T00:00:00");
        capture("primary", "2026-08-03T00:00:00Z", "2026-07-01T00:00:00", "2026-08-03T00:00:00");

        CaptureRetention.Pruned pruned = retention(true).prune();

        assertThat(pruned.deleted()).hasSize(2);
        assertThat(onDisk()).singleElement()
            .isEqualTo("sql-repository-primary-20260803T000000Z.parquet");
    }

    @Test
    void theCaptureBeforeAResetIsNeverPruned() throws IOException {
        capture("primary", "2026-06-01T00:00:00Z", "2026-01-01T00:00:00", "2026-06-01T00:00:00");
        capture("primary", "2026-08-02T00:00:00Z", "2026-08-01T00:00:00", "2026-08-02T00:00:00");
        capture("primary", "2026-08-03T00:00:00Z", "2026-08-01T00:00:00", "2026-08-03T00:00:00");

        retention(true).prune();

        assertThat(onDisk()).containsExactly(
            "sql-repository-primary-20260601T000000Z.parquet",
            "sql-repository-primary-20260803T000000Z.parquet");
    }

    @Test
    void oneSourcesCapturesNeverPruneAnothers() throws IOException {
        capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00");
        capture("primary", "2026-08-02T00:00:00Z", "2026-07-01T00:00:00", "2026-08-02T00:00:00");
        capture("replica", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00");

        retention(true).prune();

        assertThat(onDisk()).contains("sql-repository-replica-20260801T000000Z.parquet");
        assertThat(onDisk()).hasSize(2);
    }

    @Test
    void disabledItReportsWithoutDeleting() throws IOException {
        capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00");
        capture("primary", "2026-08-02T00:00:00Z", "2026-07-01T00:00:00", "2026-08-02T00:00:00");

        CaptureRetention.Pruned pruned = retention(false).prune();

        assertThat(pruned.deleted()).hasSize(1);
        assertThat(onDisk()).as("nothing actually removed").hasSize(2);
    }

    @Test
    void anUnreadableCaptureIsKept() throws IOException {
        capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00");
        capture("primary", "2026-08-02T00:00:00Z", "2026-07-01T00:00:00", "2026-08-02T00:00:00");
        Path stub = directory.resolve("sql-repository-primary-20260731T000000Z.parquet");
        java.nio.file.Files.createFile(stub);

        retention(true).prune();

        assertThat(onDisk()).contains("sql-repository-primary-20260731T000000Z.parquet");
    }

    @Test
    void aLoneCaptureIsKept() throws IOException {
        capture("primary", "2026-01-01T00:00:00Z", "2025-01-01T00:00:00", "2026-01-01T00:00:00");

        assertThat(retention(true).prune().deleted()).isEmpty();
        assertThat(onDisk()).hasSize(1);
    }
}
