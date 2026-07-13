package com.github.safetytrick.sqlexplorer.profiles;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.profiles.TableInsightsService.ColumnUsage;
import com.github.safetytrick.sqlexplorer.profiles.TableInsightsService.TableStats;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Table")
@RestController
class TableInsightsResource {

    private final TableInsightsService tableInsightsService;

    TableInsightsResource(TableInsightsService tableInsightsService) {
        this.tableInsightsService = tableInsightsService;
    }

    @GetMapping("/table/{schema}/{name}/columns")
    public List<ColumnUsage> getColumns(@PathVariable String schema, @PathVariable String name) {
        return tableInsightsService.getColumns(TableReference.newTableReference(schema, name));
    }

    @GetMapping("/table/{schema}/{name}/stats")
    public TableStats getStats(@PathVariable String schema, @PathVariable String name) {
        return tableInsightsService.getStats(TableReference.newTableReference(schema, name));
    }
}
