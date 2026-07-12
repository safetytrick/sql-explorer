package com.github.safetytrick.sqlexplorer.evidence;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.environment.DatabaseServer;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentDefinition;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import com.github.safetytrick.sqlexplorer.environment.ServerDefinition;
import com.github.safetytrick.sqlexplorer.environment.SourceDefinition;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlInput;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSourceFactory;
import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EnvironmentLoaderTest {

    private static final SqlImportStats STATS = SqlImportStats.builder().build();

    private SqlRepositoryManager sqlRepositoryManager;
    private EnvironmentRegistry registry;

    @BeforeEach
    void setUp() {
        sqlRepositoryManager = mock(SqlRepositoryManager.class);
        registry = new EnvironmentRegistry(Map.of(
            "regression", environmentDefinition("db-master", sourceDefinition("stub")),
            "empty", environmentDefinition("db-master")),
            null,
            mock(SchemaRepositoryFactory.class));
    }

    @Test
    void loadCombinesConfiguredAndExtraSources() {
        EnvironmentLoader loader = loader(new StubSourceFactory("stub", stubSource("configured")));
        when(sqlRepositoryManager.loadFromSqlSources(eq("regression"), any())).thenReturn(STATS);

        SqlImportStats stats = loader.load("regression", Stream.of(stubSource("uploaded")));

        assertThat(stats).isSameAs(STATS);
        assertThat(loadedSourceRefs("regression")).containsExactly("configured", "uploaded");
    }

    @Test
    void loadConfiguredSourcesLoadsOnlyConfigured() {
        EnvironmentLoader loader = loader(new StubSourceFactory("stub", stubSource("configured")));
        when(sqlRepositoryManager.loadFromSqlSources(eq("regression"), any())).thenReturn(STATS);

        loader.loadConfiguredSources("regression");

        assertThat(loadedSourceRefs("regression")).containsExactly("configured");
    }

    @Test
    void loadWithNoSourcesAtAllSkipsLoading() {
        EnvironmentLoader loader = loader(new StubSourceFactory("stub", stubSource("configured")));

        SqlImportStats stats = loader.load("empty", Stream.empty());

        assertThat(stats.count()).isZero();
        verifyNoInteractions(sqlRepositoryManager);
    }

    @Test
    void unknownSourceTypeFails() {
        EnvironmentLoader loader = loader(new StubSourceFactory("something-else", stubSource("configured")));

        assertThatThrownBy(() -> loader.load("regression", Stream.empty()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("stub");
    }

    private EnvironmentLoader loader(SqlSourceFactory... factories) {
        return new EnvironmentLoader(List.of(factories), sqlRepositoryManager, registry);
    }

    private List<String> loadedSourceRefs(String environmentName) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Stream<? extends SqlSource>> captor = ArgumentCaptor.forClass(Stream.class);
        verify(sqlRepositoryManager).loadFromSqlSources(eq(environmentName), captor.capture());
        return captor.getValue().map(SqlSource::getSourceRef).toList();
    }

    private static EnvironmentDefinition environmentDefinition(String serverName, SourceDefinition... sources) {
        ServerDefinition server = new ServerDefinition();
        server.setJdbcUrl("jdbc:mysql://localhost/test");
        server.setSources(List.of(sources));
        EnvironmentDefinition definition = new EnvironmentDefinition();
        definition.getServers().put(serverName, server);
        return definition;
    }

    private static SourceDefinition sourceDefinition(String type) {
        SourceDefinition definition = new SourceDefinition();
        definition.setType(type);
        return definition;
    }

    private static SqlSource stubSource(String sourceRef) {
        return new SqlSource() {
            @Override
            public Stream<SqlInput> getSql() {
                return Stream.empty();
            }

            @Override
            public Instant getCreatedOn() {
                return Instant.EPOCH;
            }

            @Override
            public String getSourceRef() {
                return sourceRef;
            }
        };
    }

    private record StubSourceFactory(String type, SqlSource source) implements SqlSourceFactory {
        @Override
        public Stream<? extends SqlSource> create(String environmentName, DatabaseServer server, SourceDefinition definition) {
            return Stream.of(source);
        }
    }
}
