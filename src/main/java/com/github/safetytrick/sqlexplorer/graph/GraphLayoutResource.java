package com.github.safetytrick.sqlexplorer.graph;

import java.util.List;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Graph")
@RestController
class GraphLayoutResource {

    private final GraphLayoutService service;

    GraphLayoutResource(GraphLayoutService service) {
        this.service = service;
    }

    @GetMapping("/graph/layout")
    public List<GraphLayoutItem> layout() {
        return service.all();
    }

    @PutMapping("/graph/layout")
    public List<GraphLayoutItem> save(@RequestBody List<GraphLayoutItem> items) {
        service.save(items);
        return service.all();
    }

    @DeleteMapping("/graph/layout/schema/{schema}")
    public List<GraphLayoutItem> reset(@PathVariable String schema) {
        service.reset(schema);
        return service.all();
    }
}
