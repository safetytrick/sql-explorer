package com.github.safetytrick.sqlexplorer.graph;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Graph")
@RestController
class GraphV2Resource {

    private final GraphV2Service graphV2Service;

    GraphV2Resource(GraphV2Service graphV2Service) {
        this.graphV2Service = graphV2Service;
    }

    @GetMapping("/graph/v2/schema/{schema}")
    public GraphV2 getSchemaGraph(@PathVariable String schema) {
        return graphV2Service.getSchemaGraph(schema);
    }
}
