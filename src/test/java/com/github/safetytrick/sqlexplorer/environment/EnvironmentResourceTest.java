package com.github.safetytrick.sqlexplorer.environment;

import java.time.Instant;
import java.util.List;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentResource.EnvironmentSummary;
import com.github.safetytrick.sqlexplorer.evidence.SqlImportStats;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryManager;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryManager.LoadState;
import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnvironmentResourceTest {

    private final EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    private final SqlRepositoryManager sqlRepositoryManager = mock(SqlRepositoryManager.class);
    private final EnvironmentResource resource = new EnvironmentResource(registry, sqlRepositoryManager, mock(com.github.safetytrick.sqlexplorer.evidence.EnvironmentLoader.class), mock(com.github.safetytrick.sqlexplorer.coverage.CoverageService.class), mock(com.github.safetytrick.sqlexplorer.evidence.CaptureRetention.class),
        everything());

    private final SchemaRepositoryFactory schemaRepositoryFactory = mock(SchemaRepositoryFactory.class);
    private final DatabaseServer server = new DatabaseServer("db-master",
        new ConnectionInfo("jdbc:mysql://localhost/test", "user", "secret"),
        List.of(), schemaRepositoryFactory);
    private final Environment environment = new Environment("regression", List.of(server));

    @AfterEach
    void tearDown() {
        environment.close();
    }

    @Test
    void reportsUnloadedState() {
        when(registry.list()).thenReturn(List.of(environment));
        when(registry.getDefaultName()).thenReturn("regression");

        EnvironmentSummary summary = resource.list().environments().getFirst();

        assertThat(summary.name()).isEqualTo("regression");
        assertThat(summary.servers()).hasSize(1);
        assertThat(summary.servers().getFirst().name()).isEqualTo("db-master");
        assertThat(summary.servers().getFirst().schemaLoaded()).isFalse();
        assertThat(summary.sources().loaded()).isFalse();
        assertThat(summary.sources().sourceCount()).isZero();
        assertThat(summary.sources().loadedOn()).isNull();
        assertThat(summary.sources().importStats()).isNull();
    }

    @Test
    void reportsLoadedState() {
        when(registry.list()).thenReturn(List.of(environment));
        when(registry.getDefaultName()).thenReturn("regression");
        SqlImportStats stats = SqlImportStats.builder().build();
        when(sqlRepositoryManager.getLoadState("regression"))
            .thenReturn(new LoadState(2, Instant.EPOCH, stats, true));

        EnvironmentSummary summary = resource.list().environments().getFirst();

        assertThat(summary.sources().loaded()).isTrue();
        assertThat(summary.sources().sourceCount()).isEqualTo(2);
        assertThat(summary.sources().loadedOn()).isEqualTo(Instant.EPOCH);
        assertThat(summary.sources().importStats()).isSameAs(stats);
    }

    @Test
    void refreshSchemaForUnknownEnvironmentFails() {
        assertThatThrownBy(() -> resource.refreshSchema("nope"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static com.github.safetytrick.sqlexplorer.auth.Authorizations everything() {
        com.github.safetytrick.sqlexplorer.auth.Authorizations all = mock(com.github.safetytrick.sqlexplorer.auth.Authorizations.class);
        org.mockito.Mockito.when(all.current()).thenReturn(com.github.safetytrick.sqlexplorer.auth.Grants.EVERYTHING);
        return all;
    }
}
