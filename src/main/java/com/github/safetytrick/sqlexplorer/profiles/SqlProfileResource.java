package com.github.safetytrick.sqlexplorer.profiles;

import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryService;
import com.github.safetytrick.sqlexplorer.model.SqlProfile;
import com.github.safetytrick.sqlexplorer.model.Table;
import com.github.safetytrick.sqlexplorer.model.TableReference;
import com.github.safetytrick.sqlexplorer.model.TableRollup;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SqlProfileResource {

    private final SqlRepositoryService sqlRepositoryService;

    public SqlProfileResource(SqlRepositoryService sqlRepositoryService) {
        this.sqlRepositoryService = sqlRepositoryService;
    }

    @GetMapping("/api/sql-explorer/analyzed-sql/{id}")
    public SqlProfile getSql(@PathVariable("id") String id) {
        SqlProfile sqlProfile = sqlRepositoryService.getById(id);
        if (sqlProfile == null) {
            throw new ResourceNotFoundException();
        }
        return sqlProfile;
    }

    @GetMapping("/api/sql-explorer/tables")
    public Stream<TableRollup> getTableNames() {
        return sqlRepositoryService.getAllTablesWithRollup();
    }

    @GetMapping("/api/sql-explorer/tables/{schema}")
    public Stream<TableRollup> getTableNamesForSchema(@PathVariable("schema") String schema) {
        return sqlRepositoryService.getTablesForSchemaWithRollup(schema);
    }

    @GetMapping("/api/sql-explorer/table/{schema}/{name}")
    public Table getTable(@PathVariable("schema") String schema,
                          @PathVariable("name") String tableName,
                          @RequestParam(name = "threshold", defaultValue = "0") int threshold) {
        return sqlRepositoryService.getTable(threshold, new TableReference(schema, tableName));
    }

    @GetMapping("/api/sql-explorer/table/{schema}/{name}/column/{column}")
    public Table getQueriesForColumn(@PathVariable("schema") String schema,
                                     @PathVariable("name") String tableName,
                                     @PathVariable("column") String column,
                                     @RequestParam(name = "threshold", defaultValue = "0") int threshold) {
        return sqlRepositoryService.getQueriesForColumn(threshold, new TableReference(schema, tableName), column);
    }

    @GetMapping("/api/sql-explorer/table/{schema}/{table}/join/{otherSchema}/{otherTable}")
    public Table getQueriesForTableJoin(@PathVariable("schema") String schema,
                                     @PathVariable("table") String tableName,
                                     @PathVariable("otherSchema") String otherSchema,
                                     @PathVariable("otherTable") String otherTable,
                                     @RequestParam(name = "threshold", defaultValue = "0") int threshold) {
        return sqlRepositoryService.getQueriesForTableJoin(threshold, new TableReference(schema, tableName), new TableReference(otherSchema, otherTable));
    }


    @ResponseStatus(value = HttpStatus.NOT_FOUND)
    public static class ResourceNotFoundException extends RuntimeException { }
}
