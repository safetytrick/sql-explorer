package com.github.safetytrick.sqlexplorer.profiles;

import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryService;
import com.github.safetytrick.sqlexplorer.model.SchemaRollup;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.model.Table;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
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
    public SqlProfile getSql(@PathVariable String id) {
        SqlProfile sqlProfile = sqlRepositoryService.getById(id);
        if (sqlProfile == null) {
            throw new ResourceNotFoundException();
        }
        return sqlProfile;
    }
    @GetMapping("/api/sql-explorer/schemas")
    public Stream<SchemaRollup> getSchemas() { return sqlRepositoryService.getAllSchemasWithRollup(); }

    @GetMapping("/api/sql-explorer/tables")
    public Stream<TableRollup> getTableNames() {
        return sqlRepositoryService.getAllTablesWithRollup();
    }

    @GetMapping("/api/sql-explorer/tables/{schema}")
    public Stream<TableRollup> getTableNamesForSchema(@PathVariable String schema) {
        return sqlRepositoryService.getTablesForSchemaWithRollup(schema);
    }

    @GetMapping("/api/sql-explorer/table/{schema}/{name}")
    public Table getTable(@PathVariable String schema,
                          @PathVariable("name") String tableName,
                          @RequestParam(defaultValue = "0") int threshold) {
        return sqlRepositoryService.getTable(threshold, TableReference.newTableReference(schema, tableName));
    }

    @GetMapping("/api/sql-explorer/table/{schema}/{name}/column/{column}")
    public Table getQueriesForColumn(@PathVariable String schema,
                                     @PathVariable("name") String tableName,
                                     @PathVariable String column,
                                     @RequestParam(defaultValue = "0") int threshold) {
        return sqlRepositoryService.getQueriesForColumn(threshold, TableReference.newTableReference(schema, tableName), column);
    }

    @GetMapping("/api/sql-explorer/table/{schema}/{table}/join/{otherSchema}/{otherTable}")
    public Table getQueriesForTableJoin(@PathVariable String schema,
                                     @PathVariable("table") String tableName,
                                     @PathVariable String otherSchema,
                                     @PathVariable String otherTable,
                                     @RequestParam(defaultValue = "0") int threshold) {
        return sqlRepositoryService.getQueriesForTableJoin(threshold, TableReference.newTableReference(schema, tableName), TableReference.newTableReference(otherSchema, otherTable));
    }


    @ResponseStatus(value = HttpStatus.NOT_FOUND)
    public static class ResourceNotFoundException extends RuntimeException { }
}
