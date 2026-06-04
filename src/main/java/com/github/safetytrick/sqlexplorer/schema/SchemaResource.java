package com.github.safetytrick.sqlexplorer.schema;

import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.model.TableRollup;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Tag(name="Schema")
@RestController
class SchemaResource {

    private final SqlMetadataService sqlMetadataService;
    private final TableReportService tableReportService;

    public SchemaResource(SqlMetadataService sqlMetadataService, TableReportService tableReportService) {
        this.sqlMetadataService = sqlMetadataService;
        this.tableReportService = tableReportService;
    }

    @GetMapping("/metadata/table/{schema}/{name}")
    public TableStructure getTableStructure(@PathVariable String schema, @PathVariable("name") String tableName) {
        return sqlMetadataService.getTable(TableReference.newTableReference(schema, tableName));
    }


    @GetMapping("/tables/with-column/{name}")
    public Stream<TableRollup> getTablesWithColumn(@PathVariable String name) {
        return tableReportService.getTablesWithColumn(name);
    }
}
