package com.github.safetytrick.sqlexplorer.evidence;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlInput;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A source scope narrows the data, not the matches: the corpus is merged from
 * the chosen sources alone, so a statement that ran on two servers carries
 * only the chosen one's figures (sqlx-a9h.1).
 */
class SourceScopeTest {

    private static final LocalDateTime START = LocalDateTime.parse("2026-11-01T00:00:00");

    /** The same statement, captured on each source with its own executions. */
    private static final Map<String, Long> EXECUTIONS = Map.of("shard1", 10L, "shard1-replica", 30L);

    private SqlRepositoryManager manager;

    private static SqlProfile profile(String sourceRef, long executions) {
        RuntimeMetrics metrics = new RuntimeMetrics(false, executions, 0L, 0L, BigDecimal.ONE, BigDecimal.ZERO,
            BigDecimal.ZERO, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
            "d1", START.plusDays(1), START, BigDecimal.ZERO, 0L, 0L, List.of());
        SqlProfile profile = new SqlProfile();
        profile.setId(sourceRef + "-1");
        profile.setQuery("select id from account");
        profile.setType("SELECT");
        profile.setDigest("d1-" + sourceRef);
        profile.setNormalizedDigest("d1");
        profile.setSchemaName("app_main");
        profile.setQueryActivityStatus(QueryActivityStatus.UNKNOWN);
        profile.setRuntimeMetrics(metrics);
        profile.setTables(List.of());
        profile.setSources(List.of(sourceRef));
        return profile;
    }

    private static SqlSource source(String ref) {
        return new SqlSource() {
            @Override public Stream<SqlInput> getSql() { return Stream.empty(); }
            @Override public Instant getCreatedOn() { return Instant.EPOCH; }
            @Override public String getSourceRef() { return ref; }
        };
    }

    private static SqlRepository repositoryFor(SqlSource source) {
        SqlProfile profile = profile(source.getSourceRef(), EXECUTIONS.get(source.getSourceRef()));
        return new SqlRepository() {
            @Override public SqlProfile getByDigest(String id) { return null; }
            @Override public Stream<SqlProfile> getTableQueries(TableReference t) { return Stream.empty(); }
            @Override public Stream<SqlProfile> getQueriesForColumn(TableReference t, String c) { return Stream.empty(); }
            @Override public Stream<TableReference> getAllTableReferences() { return Stream.empty(); }
            @Override public Stream<SqlProfile> getAllAnalyzedSql() { return Stream.of(profile); }
            @Override public SqlSource getSource() { return source; }
            @Override public SqlImportStats getImportStats() { return SqlImportStats.builder().build(); }
            @Override public CaptureContext getCaptureContext() { return null; }
        };
    }

    @BeforeEach
    void setUp() {
        SqlEvidenceInputService input = mock(SqlEvidenceInputService.class);
        when(input.fromPerformanceSchema(any(), any(), any(), any()))
            .thenAnswer(call -> repositoryFor(call.getArgument(0)));
        EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
        when(registry.get(anyString())).thenReturn(mock(Environment.class));
        manager = new SqlRepositoryManager(input, registry, mock(CaptureRehydrator.class), 4, Clock.systemUTC());
        manager.loadFromSqlSources("env", Stream.of(source("shard1"), source("shard1-replica")));
    }

    private SqlProfile only(SourceScope scope) {
        List<SqlProfile> corpus = manager.getSqlRepository("env", scope).getAllAnalyzedSql().toList();
        assertThat(corpus).hasSize(1);
        return corpus.getFirst();
    }

    @Test
    void everySourceMergesTheStatementAndNamesBoth() {
        SqlProfile merged = only(SourceScope.EVERYTHING);

        assertThat(merged.getRuntimeMetrics().getExecuted()).isEqualTo(40);
        assertThat(merged.getSources()).containsExactlyInAnyOrder("shard1", "shard1-replica");
    }

    @Test
    void aScopeCarriesOnlyItsSourcesFigures() {
        SqlProfile replica = only(SourceScope.only("shard1-replica"));

        assertThat(replica.getRuntimeMetrics().getExecuted()).isEqualTo(30);
        assertThat(replica.getSources()).containsExactly("shard1-replica");
    }

    @Test
    void anExclusionLeavesTheSourceOutOfTheData() {
        SqlProfile rest = only(SourceScope.of(List.of(), List.of("shard1-replica")));

        assertThat(rest.getRuntimeMetrics().getExecuted()).isEqualTo(10);
    }

    @Test
    void aScopeNamingNoLoadedSourceIsEmpty() {
        assertThat(manager.getSqlRepository("env", SourceScope.only("nowhere")).getAllAnalyzedSql()).isEmpty();
    }

    /** Merged once per load and scope; a new load is a new corpus, so nothing stale survives it. */
    @Test
    void aScopedCorpusLivesExactlyAsLongAsItsLoad() {
        SqlRepository first = manager.getSqlRepository("env", SourceScope.only("shard1"));

        assertThat(manager.getSqlRepository("env", SourceScope.only("SHARD1"))).isSameAs(first);
        manager.loadFromSqlSources("env", Stream.of(source("shard1"), source("shard1-replica")));
        assertThat(manager.getSqlRepository("env", SourceScope.only("shard1"))).isNotSameAs(first);
    }

    @Test
    void refsCompareWithoutCaseAndBlanksDoNotConstrain() {
        SourceScope scope = SourceScope.of(List.of("Shard1", " "), null);

        assertThat(scope.includes("shard1")).isTrue();
        assertThat(scope.includes("shard1-replica")).isFalse();
        assertThat(SourceScope.of(List.of(""), List.of()).isEverything()).isTrue();
        assertThat(SourceScope.of(List.of(), List.of("a")).includes("b")).isTrue();
        assertThat(SourceScope.of(List.of("a"), List.of("a")).includes("a")).isFalse();
    }
}
