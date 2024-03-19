package com.github.safetytrick.sqlexplorer.schema;

import com.github.safetytrick.sqlexplorer.model.TableReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SqlMetadataResource {

    private final SqlMetadataService sqlMetadataService;

    public SqlMetadataResource(SqlMetadataService sqlMetadataService) {
        this.sqlMetadataService = sqlMetadataService;
    }

    @GetMapping("/api/sql-explorer/metadata/table/{schema}/{name}")
    public TableStructure getTableStructure(@PathVariable String schema, @PathVariable("name") String tableName) {
        return sqlMetadataService.getTable(new TableReference(schema, tableName));
    }
}
