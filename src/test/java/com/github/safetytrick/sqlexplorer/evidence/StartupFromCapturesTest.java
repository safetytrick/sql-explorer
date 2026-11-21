package com.github.safetytrick.sqlexplorer.evidence;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.analysis.AnalysisFailureRecorder;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.environment.ConnectionInfo;
import com.github.safetytrick.sqlexplorer.environment.DatabaseServer;
import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import com.github.safetytrick.sqlexplorer.environment.SourceDefinition;
import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlInput;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSourceFactory;
import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StartupFromCapturesTest {

    private static SqlSource source(String ref) {
        return new SqlSource() {
            @Override public Stream<SqlInput> getSql() { return Stream.empty(); }
            @Override public Instant getCreatedOn() { return Instant.EPOCH; }
            @Override public String getSourceRef() { return ref; }
        };
    }

    private static SqlRepository captured(String ref) {
        return new SqlRepository() {
            @Override public SqlProfile getByDigest(String id) { return null; }
            @Override public Stream<SqlProfile> getTableQueries(TableReference t) { return Stream.empty(); }
            @Override public Stream<SqlProfile> getQueriesForColumn(TableReference t, String c) { return Stream.empty(); }
            @Override public Stream<TableReference> getAllTableReferences() { return Stream.empty(); }
            @Override public Stream<SqlProfile> getAllAnalyzedSql() { return Stream.empty(); }
            @Override public SqlSource getSource() { return source(ref); }
            @Override public SqlImportStats getImportStats() { return SqlImportStats.builder().build(); }
            @Override public CaptureContext getCaptureContext() { return null; }
        };
    }

    private static SqlRepositoryManager manager(String... capturedRefs) {
        CaptureRehydrator rehydrator = mock(CaptureRehydrator.class);
        for (String ref : capturedRefs) {
            when(rehydrator.rehydrate(ref)).thenReturn(captured(ref));
        }
        return new SqlRepositoryManager(mock(SqlEvidenceInputService.class), mock(EnvironmentRegistry.class),
            rehydrator, 4, Clock.systemUTC());
    }

    @Test
    void whatWasCapturedIsServedAndWhatWasNotIsListed() {
        SqlRepositoryManager manager = manager("shard1", "shard2");

        int served = manager.serveFromCaptures("production", List.of("shard1", "sharing", "shard2"));

        assertThat(served).isEqualTo(2);
        assertThat(manager.getRepositories("production"))
            .extracting(repository -> repository.getSource().getSourceRef())
            .containsExactly("shard1", "shard2");
        assertThat(manager.getFailedSources("production")).singleElement().satisfies(failure -> {
            assertThat(failure.sourceRef()).isEqualTo("sharing");
            assertThat(failure.reason()).contains("read live by the pull that follows startup");
        });
    }

    @Test
    void anEnvironmentWithNoCaptureStaysUnloaded() {
        SqlRepositoryManager manager = manager();

        assertThat(manager.serveFromCaptures("production", List.of("shard1"))).isZero();
        assertThat(manager.getLoadState("production")).isNull();
    }

    @Test
    void startupServesEveryEnvironmentDefaultFirstAndToleratesFailure() {
        List<String> asked = new ArrayList<>();
        SqlRepositoryManager manager = mock(SqlRepositoryManager.class);
        when(manager.serveFromCaptures(anyString(), org.mockito.ArgumentMatchers.anyList())).thenAnswer(call -> {
            String environment = call.getArgument(0);
            asked.add(environment + call.getArgument(1));
            if (environment.equals("broken")) {
                throw new IllegalStateException("unreadable archive");
            }
            return 1;
        });
        SqlSourceFactory factory = new SqlSourceFactory() {
            @Override public String type() { return "fake"; }
            @Override public Stream<? extends SqlSource> create(String environment, DatabaseServer server,
                                                                SourceDefinition definition) {
                return Stream.of(source(server.getName()));
            }
        };
        EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
        List<Environment> environments = Stream.of("local", "broken", "production")
            .map(name -> new Environment(name, List.of(server(name + "-a"), server(name + "-b"))))
            .toList();
        when(registry.list()).thenReturn(environments);
        when(registry.getDefaultName()).thenReturn("production");
        for (Environment environment : environments) {
            when(registry.get(environment.getName())).thenReturn(environment);
        }

        new EnvironmentLoader(List.of(factory), manager, registry, mock(AnalysisFailureRecorder.class))
            .serveCapturesAtStartup();

        assertThat(asked).containsExactly(
            "production[production-a, production-b]", "local[local-a, local-b]", "broken[broken-a, broken-b]");
    }

    private static DatabaseServer server(String name) {
        SourceDefinition definition = new SourceDefinition();
        definition.setType("fake");
        return new DatabaseServer(name, new ConnectionInfo("jdbc:x", "u", "p"), List.of(definition),
            mock(SchemaRepositoryFactory.class));
    }
}
