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

/**
 * One source that cannot be read must not cost the others their load.
 *
 * <p>Observed on a seventeen-server production environment: one instance
 * lacked the reading user, refused the login, and the exception took the
 * whole environment down — at startup and on every pull (sqlx-jdf).
 */
class PartialLoadTest {

    private SqlRepositoryManager manager;

    /** A source whose read fails the way a refused login does: wrapped, with the cause inside. */
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
        // getSql() is called by the manager before the input service sees the
        // stream, which is where a refused login surfaces in the real source.
        SqlEvidenceInputService input = mock(SqlEvidenceInputService.class);
        when(input.fromPerformanceSchema(any(), any(), any()))
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
            // the cause's message, not the wrapper's: this is what someone has to act on
            assertThat(failure.reason()).isEqualTo("Access denied for user 'reader'@'10.0.0.1' (using password: YES)");
        });
    }

    /** With nothing readable there is no corpus to keep; the previous load stays and the failure is the answer. */
    @Test
    void whenEverySourceFailsTheLoadFailsAndThePreviousCorpusStays() {
        manager.loadFromSqlSources("production", Stream.of(readable("shard1")));

        assertThatThrownBy(() -> manager.loadFromSqlSources("production", Stream.of(refusing("shard1"))))
            .hasMessageContaining("Unable to load sql from: shard1");

        assertThat(manager.getRepositories("production"))
            .extracting(repository -> repository.getSource().getSourceRef())
            .containsExactly("shard1");
        assertThat(manager.getFailedSources("production")).isEmpty();
    }

    /** A later load that reaches the server again clears what the earlier one recorded. */
    @Test
    void aLaterLoadThatReadsEverySourceClearsTheRecord() {
        manager.loadFromSqlSources("production", Stream.of(readable("shard1"), refusing("sharing")));
        manager.loadFromSqlSources("production", Stream.of(readable("shard1"), readable("sharing")));

        assertThat(manager.getFailedSources("production")).isEqualTo(List.of());
    }
}
