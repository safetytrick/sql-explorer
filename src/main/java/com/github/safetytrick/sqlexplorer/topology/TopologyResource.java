package com.github.safetytrick.sqlexplorer.topology;

import java.util.List;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentScoped;
import com.github.safetytrick.sqlexplorer.profiles.QuerySearchRequest;
import com.github.safetytrick.sqlexplorer.profiles.QuerySearchService;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The environment's servers, and what each one ran (sqlx-a9h). Reads the
 * loaded statements only; no server is contacted. A server's own statements
 * are a search scoped to its source ({@code sources=<server>}), not an
 * endpoint of this page (sqlx-a9h.1).
 */
@Tag(name = "Topology")
@EnvironmentScoped
@RestController
class TopologyResource {

    private final TopologyService topology;
    private final ServerComparisonService comparison;
    private final QuerySearchService search;
    private final EnvironmentContext environmentContext;

    TopologyResource(TopologyService topology, ServerComparisonService comparison, QuerySearchService search,
                     EnvironmentContext environmentContext) {
        this.topology = topology;
        this.comparison = comparison;
        this.search = search;
        this.environmentContext = environmentContext;
    }

    /** Clusters and servers of the environment selected on the request, with reads, writes and seconds. */
    @GetMapping("/topology")
    public Topology.Report current() {
        return topology.report(environmentContext.current().getName());
    }

    /**
     * The queries a workbench search matches (limit and offset are ignored),
     * compared across one cluster's servers and flagged where they differ
     * (sqlx-a9h.2). Rows without a flag are left out unless {@code all}; with
     * {@code flags}, only rows carrying one of them are kept.
     */
    @PostMapping("/topology/compare")
    public ServerComparison.Report compare(@RequestBody(required = false) QuerySearchRequest request,
                                           @RequestParam(required = false) String cluster,
                                           @RequestParam(defaultValue = "QUERY") ServerComparison.By by,
                                           @RequestParam(required = false) List<ServerComparison.Flag> flags,
                                           @RequestParam(defaultValue = "false") boolean all,
                                           @RequestParam(defaultValue = "DIFFERENCE") ServerComparison.Sort sort,
                                           @RequestParam(required = false) Integer limit) {
        return comparison.compare(environmentContext.current().getName(), search.matchingProfiles(request),
            new ServerComparisonService.Options(cluster, by, flags == null ? Set.of() : Set.copyOf(flags), all,
                sort, limit));
    }
}
