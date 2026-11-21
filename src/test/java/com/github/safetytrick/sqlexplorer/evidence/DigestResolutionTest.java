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
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetSqlRepository;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlInput;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class DigestResolutionTest {

    @TempDir
    Path directory;

    private static final String NORMALIZED = "v3:aaaa";
    private static final String PERF = "f2b9be5f";

    private static RuntimeMetrics metrics() {
        return RuntimeMetrics.newBuilder()
            .setFullTableScan(false).setExecuted(10).setErrors(0).setWarnings(0)
            .setTotalTime(BigDecimal.TEN).setMaxTime(BigDecimal.ONE).setAverageTime(BigDecimal.ONE)
            .setRowsSent(1).setAverageRowsSent(1).setRowsScanned(1).setAverageRowsScanned(1)
            .setTempTables(0).setTempDiskTables(0).setRowsSorted(0).setSortMergePasses(0)
            .setDigest(PERF).setDigestList(List.of(PERF))
            .setFirstSeen(LocalDateTime.parse("2026-05-04T12:00:00"))
            .setLastSeen(LocalDateTime.parse("2026-08-30T12:00:00"))
            .setLockTime(BigDecimal.ZERO).setRowsAffected(0).setAverageRowsAffected(0)
            .completeBuild();
    }

    private static SqlProfile profile() {
        return SqlProfile.newBuilder()
            .setId("primary@2026-08-31T00:00:00Z-0")
            .setQuery("select ? from t").setType("SELECT")
            .setDigest(NORMALIZED).setNormalizedDigest(NORMALIZED)
            .setSchemaName("app").setQueryActivityStatus(QueryActivityStatus.ACTIVE)
            .setRuntimeMetrics(metrics())
            .completeBuild("app");
    }

    private static SqlSource source() {
        return new SqlSource() {
            @Override public java.util.stream.Stream<SqlInput> getSql() { return java.util.stream.Stream.empty(); }
            @Override public Instant getCreatedOn() { return Instant.EPOCH; }
            @Override public String getSourceRef() { return "primary"; }
        };
    }

    private ParquetSqlRepository parquetHolding(SqlProfile row) {
        ParquetConfig config = new ParquetConfig();
        config.setFilename(directory.resolve("sql-repository.parquet").toString());
        new ParquetSink(config).write(new CaptureContext(
            CaptureContext.Origin.PERFORMANCE_SCHEMA, "primary",
            Instant.parse("2026-08-31T00:00:00Z"), 1, null, null, Map.of()), List.of(row));
        return new ParquetSqlRepository(config);
    }

    private static AnalyzedSqlRepository heapHolding(SqlProfile row) {
        return new AnalyzedSqlRepository(source(), SqlImportStats.builder().build(), List.of(row));
    }

    @Test
    void both_resolve_the_normalized_digest() throws IOException {
        SqlProfile row = profile();

        assertThat(heapHolding(row).getByDigest(NORMALIZED)).isNotNull();
        assertThat(parquetHolding(row).getByDigest(NORMALIZED)).isNotNull();
    }

    @Test
    void both_resolve_a_performance_schema_digest() throws IOException {
        SqlProfile row = profile();

        assertThat(heapHolding(row).getByDigest(PERF)).isNotNull();
        assertThat(parquetHolding(row).getByDigest(PERF)).isNotNull();
    }

    @Test
    void neither_resolves_the_profile_id() throws IOException {
        SqlProfile row = profile();

        assertThat(heapHolding(row).getByDigest(row.getId())).isNull();
        assertThat(parquetHolding(row).getByDigest(row.getId())).isNull();
    }

    @Test
    void both_answer_nothing_for_an_unknown_digest() throws IOException {
        SqlProfile row = profile();

        assertThat(heapHolding(row).getByDigest("v3:nothing")).isNull();
        assertThat(parquetHolding(row).getByDigest("v3:nothing")).isNull();
    }

    @Test
    void an_unfiltered_read_returns_every_row() throws IOException {
        SqlProfile first = profile();
        SqlProfile second = SqlProfile.newBuilder(profile())
            .setId("primary@2026-08-31T00:00:00Z-1")
            .setDigest("v3:bbbb").setNormalizedDigest("v3:bbbb")
            .build();

        ParquetConfig config = new ParquetConfig();
        config.setFilename(directory.resolve("sql-repository.parquet").toString());
        new ParquetSink(config).write(new CaptureContext(
            CaptureContext.Origin.PERFORMANCE_SCHEMA, "primary",
            Instant.parse("2026-08-31T00:00:00Z"), 2, null, null, Map.of()), List.of(first, second));

        try (var all = new ParquetSqlRepository(config).getAllAnalyzedSql()) {
            assertThat(all.count()).isEqualTo(2);
        }
    }

    @Test
    void the_composite_agrees_with_both_of_its_sources() throws IOException {
        SqlProfile row = profile();
        CompositeSqlRepository composite =
            new CompositeSqlRepository(List.of(heapHolding(row), parquetHolding(row)));

        assertThat(composite.getByDigest(NORMALIZED)).isNotNull();
        assertThat(composite.getByDigest(PERF)).isNotNull();
        assertThat(composite.getByDigest(row.getId())).isNull();
    }
}
