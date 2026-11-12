package com.github.safetytrick.sqlexplorer.topology;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentScoped;

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
    private final EnvironmentContext environmentContext;

    TopologyResource(TopologyService topology, EnvironmentContext environmentContext) {
        this.topology = topology;
        this.environmentContext = environmentContext;
    }

    /** Clusters and servers of the environment selected on the request, with reads, writes and seconds. */
    @GetMapping("/topology")
    public Topology.Report current() {
        return topology.report(environmentContext.current().getName());
    }
}
