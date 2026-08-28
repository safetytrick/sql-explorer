package com.github.safetytrick.sqlexplorer.schema;

import java.util.List;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.model.TableRollup;
import com.github.safetytrick.sqlexplorer.schema.ForeignKeyDetailService.ForeignKeyDetail;
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
    private final ForeignKeyDetailService foreignKeyDetailService;

    public SchemaResource(SqlSchemaService sqlSchemaService,
                          TableReportService tableReportService,
                          ForeignKeyDetailService foreignKeyDetailService) {
        this.sqlSchemaService = sqlSchemaService;
        this.tableReportService = tableReportService;
        this.foreignKeyDetailService = foreignKeyDetailService;
    }

    /**
     * A table the active schema has never heard of answers <em>null</em> — an
     * empty 200 body, deliberately not a 404. The table lens reads that null as
     * "no such table" and renders a not-found page; a 404 would reach the client
     * as a transport error and would be indistinguishable from the backend being
     * down, which is the one distinction the lens has to keep.
     */
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
        return sqlSchemaService.getCreateStatement(table);
    }


    /** Foreign keys touching the table: owned ("out") and referencing ("in"). */
    @GetMapping("/metadata/table/{schema}/{name}/foreign-keys")
    public List<ForeignKeyDetail> getForeignKeys(@PathVariable String schema, @PathVariable("name") String tableName) {
        return foreignKeyDetailService.getForeignKeys(TableReference.newTableReference(schema, tableName));
    }

    @GetMapping("/tables/with-column/{name}")
    public Stream<TableRollup> getTablesWithColumn(@PathVariable String name) {
        return tableReportService.getTablesWithColumn(name);
    }
}
