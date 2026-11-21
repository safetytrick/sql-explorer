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

    @GetMapping("/topology")
    public Topology.Report current() {
        return topology.report(environmentContext.current().getName());
    }

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
