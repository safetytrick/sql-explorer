package com.github.safetytrick.sqlexplorer.graph;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentScoped;
import com.github.safetytrick.sqlexplorer.commentary.SqlTagsService;
import com.github.safetytrick.sqlexplorer.profiles.QuerySearchRequest;
import com.github.safetytrick.sqlexplorer.profiles.QuerySearchService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Graph")
@EnvironmentScoped
@RestController
class GraphV2Resource {

    private final GraphV2Service graphV2Service;
    private final SqlTagsService sqlTagsService;
    private final QuerySearchService querySearchService;

    GraphV2Resource(GraphV2Service graphV2Service, SqlTagsService sqlTagsService,
                    QuerySearchService querySearchService) {
        this.graphV2Service = graphV2Service;
        this.sqlTagsService = sqlTagsService;
        this.querySearchService = querySearchService;
    }

    @GetMapping("/graph/v2/schema/{schema}")
    public GraphV2 getSchemaGraph(@PathVariable String schema) {
        return graphV2Service.getSchemaGraph(schema);
    }

    /** Graph over the queries carrying a tag. */
    @GetMapping("/graph/v2/by-tag/{tag}")
    public GraphV2 getTagGraph(@PathVariable String tag) {
        return graphV2Service.getGraphForProfiles(sqlTagsService.getSqlByTag(tag).toList());
    }

    /**
     * Graph over the queries matching a workbench search (limit/offset are
     * ignored). With {@code allTables}, every table of the schemas the search
     * is scoped to is drawn too, queried or not; unscoped, it adds nothing.
     */
    @PostMapping("/graph/v2/search")
    public GraphV2 getSearchGraph(@RequestBody(required = false) QuerySearchRequest request,
                                  @RequestParam(defaultValue = "false") boolean allTables) {
        List<String> schemas = allTables && request != null && request.schemas() != null ? request.schemas() : List.of();
        return graphV2Service.getGraphForProfiles(querySearchService.matchingProfiles(request), schemas);
    }
}
