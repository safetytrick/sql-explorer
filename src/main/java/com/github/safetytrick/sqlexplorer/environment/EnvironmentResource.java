package com.github.safetytrick.sqlexplorer.environment;

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

    EnvironmentResource(EnvironmentRegistry registry, SqlRepositoryManager sqlRepositoryManager,
                        EnvironmentLoader environmentLoader, CoverageService coverageService,
                        CaptureRetention retention) {
        this.registry = registry;
        this.sqlRepositoryManager = sqlRepositoryManager;
        this.environmentLoader = environmentLoader;
        this.coverageService = coverageService;
        this.retention = retention;
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
            .map(server -> new ServerSummary(server.getName(), server.isSchemaLoaded(),
                server.getRoles().stream().map(ServerRole::configName).toList()))
            .toList();
        SqlRepositoryManager.LoadState state = sqlRepositoryManager.getLoadState(environment.getName());
        SourcesSummary sources = state == null
            ? SourcesSummary.NOT_LOADED
            : new SourcesSummary(true, state.sourceCount(), state.loadedOn(), state.importStats(),
                state.resident());
        return new EnvironmentSummary(environment.getName(), servers, sources, environment.isCached(),
            environmentLoader.health(environment.getName()), environment.samplingStatus());
    }

    /**
     * Pull this environment's sources now, and say what the pull captured.
     *
     * <p>Distinct from {@code POST /environments/{name}/load}, which answers
     * import statistics: this answers what a person asked, which is whether the
     * capture is worth having — how many profiles, and what period they cover.
     * A pull that succeeded silently tells you nothing about whether the server
     * had anything new to say (sqlx-ijz).
     */
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

    /**
     * @param prunedCaptures how many redundant captures went with it, or would
     *                       have — see {@code pruningEnabled}
     */
    record PullResult(String environment, int profiles, Coverage.ObservedWindow window,
                      int prunedCaptures, boolean pruningEnabled) { }

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

    /**
     * @param cached whether it is served from its cache with no server
     *               connected; a pull takes it live (sqlx-nbi)
     * @param health its state and anything wrong with it, for the selector (sqlx-nbi.8)
     * @param sampling which server sampling queries would go to, or why none (sqlx-n5j.4)
     */
    record EnvironmentSummary(String name, List<ServerSummary> servers, SourcesSummary sources, boolean cached,
                              com.github.safetytrick.sqlexplorer.evidence.EnvironmentHealth health,
                              Environment.SamplingStatus sampling) { }

    /** @param roles what config says the server is for, lowercase: primary, replica, analytics, sampling */
    record ServerSummary(String name, boolean schemaLoaded, List<String> roles) { }

    /**
     * @param resident whether the corpus is in heap right now. An environment
     *                 dropped under memory pressure is still loaded — it comes
     *                 back on the next read — so reporting it as unloaded would
     *                 be wrong, and reporting nothing would hide why its stats
     *                 are absent (sqlx-4ww)
     */
    record SourcesSummary(boolean loaded, int sourceCount, Instant loadedOn, SqlImportStats importStats,
                          boolean resident) {
        static final SourcesSummary NOT_LOADED = new SourcesSummary(false, 0, null, null, false);
    }
}
