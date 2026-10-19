package com.github.safetytrick.sqlexplorer.evidence;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.analysis.AnalyzerVersion;
import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import com.github.safetytrick.sqlexplorer.evidence.SqlEvidenceInputService.AnalysisContext;
import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlInput;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.model.MergedSqlProfile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A load reuses the analysis already in memory before reading any capture —
 * so the corpus it builds shares its analysis with the one it replaces
 * (sqlx-sqy.5).
 */
class ResidentReuseTest {

    private static SqlSource source(String ref) {
        return new SqlSource() {
            @Override public Stream<SqlInput> getSql() { return Stream.empty(); }
            @Override public Instant getCreatedOn() { return Instant.EPOCH; }
            @Override public String getSourceRef() { return ref; }
        };
    }

    private static SqlProfile analysed(String digest) {
        return SqlProfile.newBuilder()
            .setId(digest).setQuery("SELECT 1").setType("SELECT").setDigest("v3:" + digest)
            .setNormalizedDigest("v3:" + digest).setSchemaName("app")
            .setQueryActivityStatus(QueryActivityStatus.ACTIVE)
            .setTables(List.of(TableReference.newTableReference("app", "users")))
            .setRuntimeMetrics(RuntimeMetrics.newBuilder().fromPerformanceSchemaRow(row(digest)).completeBuild())
            .setAnalyzerVersion(AnalyzerVersion.CURRENT).setSessionSchema("app")
            .build();
    }

    private static com.github.safetytrick.sqlexplorer.evidence.source.MeteredSql row(String digest) {
        var row = new com.github.safetytrick.sqlexplorer.evidence.source.MeteredSql();
        row.setQuery("SELECT 1");
        row.setDigest(digest);
        row.setExecuted(1);
        row.setTotalTime(java.math.BigDecimal.ONE);
        row.setMaxTime(java.math.BigDecimal.ONE);
        row.setAverageTime(java.math.BigDecimal.ONE);
        row.setLockTime(java.math.BigDecimal.ZERO);
        row.setFirstSeen(LocalDateTime.parse("2026-10-01T00:00:00"));
        row.setLastSeen(LocalDateTime.parse("2026-10-19T00:00:00"));
        return row;
    }

    private static SqlRepository holding(String ref, SqlProfile... profiles) {
        return new SqlRepository() {
            @Override public SqlProfile getByDigest(String id) { return null; }
            @Override public Stream<SqlProfile> getTableQueries(TableReference t) { return Stream.empty(); }
            @Override public Stream<SqlProfile> getQueriesForColumn(TableReference t, String c) { return Stream.empty(); }
            @Override public Stream<TableReference> getAllTableReferences() { return Stream.empty(); }
            @Override public Stream<SqlProfile> getAllAnalyzedSql() { return Stream.of(profiles); }
            @Override public SqlSource getSource() { return source(ref); }
            @Override public SqlImportStats getImportStats() { return SqlImportStats.builder().build(); }
            @Override public CaptureContext getCaptureContext() { return null; }
        };
    }

    @Test
    void theResidentCorpusIsIndexedIncludingMergedMembersAndOnlyTheRestComesFromCaptures() {
        List<AnalysisContext> contexts = new ArrayList<>();
        SqlEvidenceInputService input = mock(SqlEvidenceInputService.class);
        // first load: shard1 holds one plain profile and one merge group of two
        when(input.fromPerformanceSchema(any(), any(), any(), any())).thenAnswer(call -> {
            contexts.add(call.getArgument(3));
            return holding("shard1", analysed("aaa"),
                new MergedSqlProfile(List.of(analysed("bbb"), analysed("ccc"))));
        });
        EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
        Environment environment = mock(Environment.class);
        when(environment.isSchemaCurrentFor(anyString())).thenReturn(true);
        when(registry.get(anyString())).thenReturn(environment);
        CaptureRehydrator rehydrator = mock(CaptureRehydrator.class);
        when(rehydrator.reuseFor(anyList())).thenReturn(AnalysisReuse.NONE);
        SqlRepositoryManager manager = new SqlRepositoryManager(input, registry, rehydrator, 4, Clock.systemUTC());

        manager.loadFromSqlSources("production", Stream.of(source("shard1")));
        manager.loadFromSqlSources("production", Stream.of(source("shard1"), source("shard2")));

        AnalysisReuse second = contexts.get(1).reuse();
        assertThat(second.find("aaa", "app")).isNotNull();
        assertThat(second.find("bbb", "app")).as("a merged member, by its own digest").isNotNull();
        assertThat(second.find("ccc", "app")).isNotNull();
        verify(rehydrator).reuseFor(List.of("shard2"));
        verify(rehydrator, never()).reuseFor(List.of("shard1", "shard2"));
    }
}
