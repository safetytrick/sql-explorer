package com.github.safetytrick.sqlexplorer.graph;

import java.util.List;
import java.util.stream.Stream;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SqlGraphResource {

    private final SqlGraphService sqlGraphService;

    public SqlGraphResource(SqlGraphService sqlGraphService) {
        this.sqlGraphService = sqlGraphService;
    }

    @GetMapping("/api/sql-explorer/graph/all")
    public Stream<TableRelationships> getTableRelationships(@RequestParam(defaultValue = "5") int threshold) {
        return sqlGraphService.getTableRelationships(threshold);
    }

    @GetMapping("/api/sql-explorer/graph/schema/{schema}")
    public Stream<TableRelationships> getTableRelationshipsForSchema(@PathVariable String schema,
                                                                     @RequestParam(defaultValue = "5") int threshold) {
        return sqlGraphService.getTableRelationshipsForSchema(threshold, schema);
    }

    @GetMapping("/api/sql-explorer/graph/schema/{schema}/table/{table}")
    public Stream<TableRelationships> getTableRelationshipsForSchemaAndTable(@PathVariable String schema,
                                                                             @PathVariable String table,
                                                                             @RequestParam(defaultValue = "0") int depth,
                                                                             @RequestParam(defaultValue = "5") int threshold) {
        return sqlGraphService.getTableRelationshipsForSchemaAndTable(threshold, depth, schema, table);
    }

    @GetMapping("/api/sql-explorer/graph/by-tag/{tag}")
    public Stream<GroupedTableRelationships> getTableRelationshipsForTag(@PathVariable String tag,
                                                                         @RequestParam(defaultValue = "0") int depth,
                                                                         @RequestParam(defaultValue = "5") int threshold) {
        return sqlGraphService.getTableRelationshipsForTag(threshold, depth, tag);
    }


    @GetMapping("/api/sql-explorer/graph/by-tag/{tag}/{schema}/join/{otherSchema}")
    public List<CrossSchemaTable> getQueriesForAllTableJoins(@PathVariable String schema,
                                                             @PathVariable String tag,
                                                             @PathVariable String otherSchema,
                                                             @RequestParam(defaultValue = "100") int graphServiceThreshold,
                                                             @RequestParam(defaultValue = "0") int graphServiceDepth,
                                                             @RequestParam(defaultValue = "0") int threshold) {
        return sqlGraphService.getInfoForCrossSchemaJoins(schema, tag, otherSchema,
                                                          graphServiceThreshold, graphServiceDepth, threshold);
    }

}
