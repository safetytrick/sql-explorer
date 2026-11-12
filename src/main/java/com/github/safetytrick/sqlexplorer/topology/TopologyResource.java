package com.github.safetytrick.sqlexplorer.topology;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The environment's servers, and what each one ran (sqlx-a9h). Reads the
 * loaded statements only; no server is contacted.
 */
@Tag(name = "Topology")
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

    /** One server's statements, the most time first. A server the environment does not have is a 404. */
    @GetMapping("/topology/server/{server}/statements")
    public Topology.ServerStatements statements(@PathVariable String server,
                                                @RequestParam(defaultValue = "25") int limit) {
        Environment environment = environmentContext.current();
        if (!topology.knows(environment.getName(), server)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no server " + server);
        }
        return topology.statements(environment.getName(), server, limit);
    }
}
