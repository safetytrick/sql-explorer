package com.github.safetytrick.sqlexplorer.schema;

import java.util.stream.Stream;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.github.safetytrick.sqlexplorer.core.TableRollup;

@RestController
public class TableReportResource {

    private final TableReportService tableReportService;

    public TableReportResource(TableReportService tableReportService) {
        this.tableReportService = tableReportService;
    }

    @GetMapping("/api/sql-explorer/tables/with-column/{name}")
    public Stream<TableRollup> getTablesWithColumn(@PathVariable("name") String name) {
        return tableReportService.getTablesWithColumn(name);
    }

}
