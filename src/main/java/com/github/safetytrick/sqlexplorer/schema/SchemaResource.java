package com.github.safetytrick.sqlexplorer.schema;

import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.model.TableRollup;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Tag(name="Schema")
@RestController
class SchemaResource {

    private final SqlSchemaService sqlSchemaService;
    private final TableReportService tableReportService;
    private final CreateStatementService createStatementService;

    public SchemaResource(SqlSchemaService sqlSchemaService,
                          TableReportService tableReportService,
                          CreateStatementService createStatementService) {
        this.sqlSchemaService = sqlSchemaService;
        this.tableReportService = tableReportService;
        this.createStatementService = createStatementService;
    }

    @GetMapping("/metadata/table/{schema}/{name}")
    public TableStructure getTableStructure(@PathVariable String schema, @PathVariable("name") String tableName) {
        return sqlSchemaService.getTable(TableReference.newTableReference(schema, tableName));
    }

    @GetMapping(value = "/metadata/table/{schema}/{name}/create", produces = "text/plain")
    public String getCreateStatement(@PathVariable String schema, @PathVariable("name") String tableName) {
        TableStructure table = sqlSchemaService.getTable(TableReference.newTableReference(schema, tableName));
        if (table == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown table: " + schema + "." + tableName);
        }
        return createStatementService.getCreateStatement(table);
    }


    @GetMapping("/tables/with-column/{name}")
    public Stream<TableRollup> getTablesWithColumn(@PathVariable String name) {
        return tableReportService.getTablesWithColumn(name);
    }
}
