package com.github.safetytrick.sqlexplorer.schema;

import java.util.List;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.github.safetytrick.sqlexplorer.core.TableReference;

@RestController
public class SqlMetadataResource {

    private final SqlMetadataService sqlMetadataService;
    private final SqlMetadataAnalysisService sqlMetadataAnalysisService;

    public SqlMetadataResource(SqlMetadataService sqlMetadataService, SqlMetadataAnalysisService sqlMetadataAnalysisService) {
        this.sqlMetadataService = sqlMetadataService;
        this.sqlMetadataAnalysisService = sqlMetadataAnalysisService;
    }

    @GetMapping("/api/sql-explorer/metadata/table/{schema}/{name}")
    public TableStructure getTableStructure(@PathVariable("schema") String schema, @PathVariable("name") String tableName) {
        return sqlMetadataService.getTable(new TableReference(schema, tableName));
    }

    @GetMapping("/api/sql-explorer/metadata/all-join-pairs")
    public Set<List<String>> getAllJoinPairs(@RequestParam(name = "threshold", defaultValue = "5") Integer threshold){
        if (threshold == null) {
            threshold = 5;
        }

        return sqlMetadataAnalysisService.getAllJoinPairs(threshold);
    }
}
