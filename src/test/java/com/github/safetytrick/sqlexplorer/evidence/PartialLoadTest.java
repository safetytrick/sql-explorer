package com.github.safetytrick.sqlexplorer.evidence;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PartialLoadTest {

    private SqlRepositoryManager manager;

    private static SqlSource refusing(String ref) {
        return new SqlSource() {
            @Override public Stream<SqlInput> getSql() {
                throw new IllegalStateException("Unable to load sql from: " + ref,
                    new SQLException("Access denied for user 'reader'@'10.0.0.1' (using password: YES)"));
            }
            @Override public Instant getCreatedOn() { return Instant.EPOCH; }
            @Override public String getSourceRef() { return ref; }
        };
    }

    private static SqlSource readable(String ref) {
        return new SqlSource() {
            @Override public Stream<SqlInput> getSql() { return Stream.empty(); }
            @Override public Instant getCreatedOn() { return Instant.EPOCH; }
            @Override public String getSourceRef() { return ref; }
        };
    }

    private static SqlRepository repositoryFor(SqlSource source) {
        return new SqlRepository() {
            @Override public SqlProfile getByDigest(String id) { return null; }
            @Override public Stream<SqlProfile> getTableQueries(TableReference t) { return Stream.empty(); }
            @Override public Stream<SqlProfile> getQueriesForColumn(TableReference t, String c) { return Stream.empty(); }
            @Override public Stream<TableReference> getAllTableReferences() { return Stream.empty(); }
            @Override public Stream<SqlProfile> getAllAnalyzedSql() { return Stream.empty(); }
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
    }

    @Test
    void theReadableSourcesLoadAndTheUnreadableOneIsRecorded() {
        manager.loadFromSqlSources("production",
            Stream.of(readable("shard1"), refusing("sharing"), readable("shard2")));

        assertThat(manager.getRepositories("production"))
            .extracting(repository -> repository.getSource().getSourceRef())
            .containsExactlyInAnyOrder("shard1", "shard2");
        assertThat(manager.getFailedSources("production")).singleElement().satisfies(failure -> {
            assertThat(failure.sourceRef()).isEqualTo("sharing");
            assertThat(failure.reason()).isEqualTo("Access denied for user 'reader'@'10.0.0.1' (using password: YES)");
        });
    }

    @Test
    void whenEverySourceFailsTheLoadFailsAndThePreviousCorpusStays() {
        manager.loadFromSqlSources("production", Stream.of(readable("shard1")));

        assertThatThrownBy(() -> manager.loadFromSqlSources("production", Stream.of(refusing("shard1"))))
            .hasMessageContaining("Unable to load sql from: shard1");

        assertThat(manager.getRepositories("production"))
            .extracting(repository -> repository.getSource().getSourceRef())
            .containsExactly("shard1");
        assertThat(manager.getFailedSources("production")).extracting(SqlRepositoryManager.SourceFailure::sourceRef)
            .containsExactly("shard1");
    }

    @Test
    void aSourceThatFailsKeepsWhatItWasServedFrom() {
        manager.loadFromSqlSources("production", Stream.of(readable("shard1"), readable("sharing")));
        SqlRepository before = manager.getRepositories("production").stream()
            .filter(repository -> repository.getSource().getSourceRef().equals("sharing")).findFirst().orElseThrow();

        manager.loadFromSqlSources("production", Stream.of(readable("shard1"), refusing("sharing")));

        assertThat(manager.getRepositories("production")).contains(before)
            .extracting(repository -> repository.getSource().getSourceRef())
            .containsExactlyInAnyOrder("shard1", "sharing");
        assertThat(manager.getFailedSources("production")).extracting(SqlRepositoryManager.SourceFailure::sourceRef)
            .containsExactly("sharing");
        assertThat(manager.servedCaptures("production")).containsOnlyKeys("shard1", "sharing");
    }

    @Test
    void aLaterLoadThatReadsEverySourceClearsTheRecord() {
        manager.loadFromSqlSources("production", Stream.of(readable("shard1"), refusing("sharing")));
        manager.loadFromSqlSources("production", Stream.of(readable("shard1"), readable("sharing")));

        assertThat(manager.getFailedSources("production")).isEqualTo(List.of());
    }
}
