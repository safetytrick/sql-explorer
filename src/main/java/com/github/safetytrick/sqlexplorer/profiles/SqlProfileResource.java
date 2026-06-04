package com.github.safetytrick.sqlexplorer.profiles;

import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryService;
import com.github.safetytrick.sqlexplorer.model.SchemaRollup;
import com.github.safetytrick.sqlexplorer.model.Table;
import com.github.safetytrick.sqlexplorer.model.TableRollup;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name="SqlProfile")
@RestController
class SqlProfileResource {

    private final SqlRepositoryService sqlRepositoryService;

    public SqlProfileResource(SqlRepositoryService sqlRepositoryService) {
        this.sqlRepositoryService = sqlRepositoryService;
    }

    @GetMapping("/analyzed-sql/{id}")
    public SqlProfile getSql(@PathVariable String id) {
        SqlProfile sqlProfile = sqlRepositoryService.getById(id);
        if (sqlProfile == null) {
            throw new ResourceNotFoundException();
        }
        return sqlProfile;
    }
    @GetMapping("/schemas")
    public Stream<SchemaRollup> getSchemas() { return sqlRepositoryService.getAllSchemasWithRollup(); }

    @GetMapping("/tables")
    public Stream<TableRollup> getTableNames() {
        return sqlRepositoryService.getAllTablesWithRollup();
    }

    @GetMapping("/tables/{schema}")
    public Stream<TableRollup> getTableNamesForSchema(@PathVariable String schema) {
        return sqlRepositoryService.getTablesForSchemaWithRollup(schema);
    }

    @GetMapping("/table/{schema}/{name}")
    public Table getTable(@PathVariable String schema,
                          @PathVariable("name") String tableName,
                          @RequestParam(defaultValue = "0") int threshold) {
        return sqlRepositoryService.getTable(threshold, TableReference.newTableReference(schema, tableName));
    }

    @GetMapping("/table/{schema}/{name}/column/{column}")
    public Table getQueriesForColumn(@PathVariable String schema,
                                     @PathVariable("name") String tableName,
                                     @PathVariable String column,
                                     @RequestParam(defaultValue = "0") int threshold) {
        return sqlRepositoryService.getQueriesForColumn(threshold, TableReference.newTableReference(schema, tableName), column);
    }

    @GetMapping("/table/{schema}/{table}/join/{otherSchema}/{otherTable}")
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
