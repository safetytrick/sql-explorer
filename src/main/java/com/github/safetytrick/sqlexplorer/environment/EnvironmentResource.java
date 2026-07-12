package com.github.safetytrick.sqlexplorer.environment;

import java.time.Instant;
import java.util.List;

import com.github.safetytrick.sqlexplorer.evidence.SqlImportStats;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryManager;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Tag(name = "Environment")
@RestController
class EnvironmentResource {

    private final EnvironmentRegistry registry;
    private final SqlRepositoryManager sqlRepositoryManager;

    EnvironmentResource(EnvironmentRegistry registry, SqlRepositoryManager sqlRepositoryManager) {
        this.registry = registry;
        this.sqlRepositoryManager = sqlRepositoryManager;
    }

    @GetMapping("/environments")
    public EnvironmentList list() {
        List<EnvironmentSummary> environments = registry.list().stream()
            .map(this::summarize)
            .toList();
        return new EnvironmentList(registry.getDefaultName(), environments);
    }

    private EnvironmentSummary summarize(Environment environment) {
        List<ServerSummary> servers = environment.getServers().stream()
            .map(server -> new ServerSummary(server.getName(), server.isSchemaLoaded()))
            .toList();
        SqlRepositoryManager.LoadState state = sqlRepositoryManager.getLoadState(environment.getName());
        SourcesSummary sources = state == null
            ? SourcesSummary.NOT_LOADED
            : new SourcesSummary(true, state.sourceCount(), state.loadedOn(), state.importStats());
        return new EnvironmentSummary(environment.getName(), servers, sources);
    }

    /** Drop the environment's cached schema so it reloads from its servers on next use. */
    @PostMapping("/environments/{name}/refresh-schema")
    public ResponseEntity<Void> refreshSchema(@PathVariable String name) {
        Environment environment = registry.get(name);
        if (environment == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown environment: " + name);
        }
        environment.refreshSchema();
        return ResponseEntity.noContent().build();
    }

    record EnvironmentList(String defaultEnvironment, List<EnvironmentSummary> environments) { }

    record EnvironmentSummary(String name, List<ServerSummary> servers, SourcesSummary sources) { }

    record ServerSummary(String name, boolean schemaLoaded) { }

    record SourcesSummary(boolean loaded, int sourceCount, Instant loadedOn, SqlImportStats importStats) {
        static final SourcesSummary NOT_LOADED = new SourcesSummary(false, 0, null, null);
    }
}
