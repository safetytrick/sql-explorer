package com.github.safetytrick.sqlexplorer.schema;

import java.util.List;
import java.util.stream.Stream;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.github.safetytrick.sqlexplorer.core.CrossSchemaTable;

@RestController
public class SqlGraphResource {

    private final SqlGraphService sqlGraphService;

    public SqlGraphResource(SqlGraphService sqlGraphService) {
        this.sqlGraphService = sqlGraphService;
    }

    @GetMapping("/api/sql-explorer/graph/all")
    public Stream<TableRelationships> getTableForeignKeys(@RequestParam(name = "threshold", defaultValue = "5") int threshold) {
        return sqlGraphService.getTableForeignKeys(threshold);
    }

    @GetMapping("/api/sql-explorer/graph/schema/{schema}")
    public Stream<TableRelationships> getTableForeignKeysForSchema(@PathVariable("schema") String schema,
                                                                   @RequestParam(name = "threshold", defaultValue = "5") int threshold) {
        return sqlGraphService.getTableForeignKeysForSchema(threshold, schema);
    }

    @GetMapping("/api/sql-explorer/graph/schema/{schema}/table/{table}")
    public Stream<TableRelationships> getTableForeignKeysForSchemaAndTable(@PathVariable("schema") String schema,
                                                                           @PathVariable("table") String table,
                                                                           @RequestParam(name = "depth", defaultValue = "0") int depth,
                                                                           @RequestParam(name = "threshold", defaultValue = "5") int threshold) {
        return sqlGraphService.getTableForeignKeysForSchemaAndTable(threshold, depth, schema, table);
    }

    @GetMapping("/api/sql-explorer/graph/by-tag/{tag}")
    public Stream<GroupedTableRelationships> getTableForeignKeysForTag(@PathVariable("tag") String tag,
                                                                @RequestParam(name = "depth", defaultValue = "0") int depth,
                                                                @RequestParam(name = "threshold", defaultValue = "5") int threshold) {
        return sqlGraphService.getTableForeignKeysForTag(threshold, depth, tag);
    }


    @GetMapping("/api/sql-explorer/graph/by-tag/{tag}/{schema}/join/{otherSchema}")
    public List<CrossSchemaTable> getQueriesForAllTableJoins(@PathVariable("schema") String schema,
                                                             @PathVariable("tag") String tag,
                                                             @PathVariable("otherSchema") String otherSchema,
                                                             @RequestParam(name = "graphServiceThreshold",
                                                                           defaultValue = "100") int graphServiceThreshold,
                                                             @RequestParam(name = "graphServiceDepth",
                                                                           defaultValue = "0") int graphServiceDepth,
                                                             @RequestParam(name = "threshold",
                                                                           defaultValue = "0") int threshold) {
        return sqlGraphService.getInfoForCrossSchemaJoins(schema, tag, otherSchema,
                                                          graphServiceThreshold, graphServiceDepth, threshold);
    }

}
