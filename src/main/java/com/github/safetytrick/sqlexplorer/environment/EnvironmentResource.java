package com.github.safetytrick.sqlexplorer.environment;

import com.github.safetytrick.sqlexplorer.auth.Authorizations;
import com.github.safetytrick.sqlexplorer.auth.Grants;
import com.github.safetytrick.sqlexplorer.auth.Permission;

import java.time.Instant;
import java.util.List;

import com.github.safetytrick.sqlexplorer.coverage.Coverage;
import com.github.safetytrick.sqlexplorer.coverage.CoverageService;
import com.github.safetytrick.sqlexplorer.evidence.CaptureRetention;
import com.github.safetytrick.sqlexplorer.evidence.EnvironmentLoader;
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
    private final EnvironmentLoader environmentLoader;
    private final CoverageService coverageService;
    private final CaptureRetention retention;
    private final Authorizations authorizations;

    EnvironmentResource(EnvironmentRegistry registry, SqlRepositoryManager sqlRepositoryManager,
                        EnvironmentLoader environmentLoader, CoverageService coverageService,
                        CaptureRetention retention, Authorizations authorizations) {
        this.authorizations = authorizations;
        this.registry = registry;
        this.sqlRepositoryManager = sqlRepositoryManager;
        this.environmentLoader = environmentLoader;
        this.coverageService = coverageService;
        this.retention = retention;
    }

    @GetMapping("/environments")
    public EnvironmentList list() {
        Grants grants = authorizations.current();
        List<EnvironmentSummary> environments = registry.list().stream()
            .filter(environment -> grants.allows(Permission.EXPLORER_READ, environment.getName()))
            .map(this::summarize)
            .toList();
        String preferred = registry.getDefaultName();
        String defaultName = environments.stream().anyMatch(e -> e.name().equals(preferred)) ? preferred
            : environments.isEmpty() ? null : environments.getFirst().name();
        return new EnvironmentList(defaultName, environments);
    }

    private EnvironmentSummary summarize(Environment environment) {
        java.util.Map<DatabaseServer, String> clusterOf = new java.util.HashMap<>();
        for (Cluster cluster : environment.getClusters()) {
            cluster.servers().forEach(server -> clusterOf.putIfAbsent(server, cluster.name()));
        }
        List<ServerSummary> servers = environment.getServers().stream()
            .map(server -> new ServerSummary(server.getName(), server.isSchemaLoaded(),
                server.getRoles().stream().map(ServerRole::configName).toList(), clusterOf.get(server)))
            .toList();
        SqlRepositoryManager.LoadState state = sqlRepositoryManager.getLoadState(environment.getName());
        SourcesSummary sources = state == null
            ? SourcesSummary.NOT_LOADED
            : new SourcesSummary(true, state.sourceCount(), state.loadedOn(), state.importStats(),
                state.resident());
        return new EnvironmentSummary(environment.getName(), servers, sources, environment.isCached(),
            environmentLoader.health(environment.getName()), environment.samplingStatus());
    }

    @PostMapping("/environments/{name}/pull")
    public PullResult pull(@PathVariable String name) {
        Environment environment = registry.get(name);
        if (environment == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown environment: " + name);
        }
        SqlImportStats stats = environmentLoader.loadConfiguredSources(environment.getName());
        Coverage.Report coverage = coverageService.report(environment.getName());
        Coverage.ObservedWindow window = coverage.sources().stream()
            .map(Coverage.SourceCoverage::window)
            .filter(java.util.Objects::nonNull)
            .findFirst().orElse(null);
        CaptureRetention.Pruned pruned = retention.prune();
        return new PullResult(environment.getName(), stats.count(), window,
            pruned.deleted().size(), retention.isEnabled());
    }

    record PullResult(String environment, int profiles, Coverage.ObservedWindow window,
                      int prunedCaptures, boolean pruningEnabled) { }

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

    record EnvironmentSummary(String name, List<ServerSummary> servers, SourcesSummary sources, boolean cached,
                              com.github.safetytrick.sqlexplorer.evidence.EnvironmentHealth health,
                              Environment.SamplingStatus sampling) { }

    record ServerSummary(String name, boolean schemaLoaded, List<String> roles, String cluster) { }

    record SourcesSummary(boolean loaded, int sourceCount, Instant loadedOn, SqlImportStats importStats,
                          boolean resident) {
        static final SourcesSummary NOT_LOADED = new SourcesSummary(false, 0, null, null, false);
    }
}
