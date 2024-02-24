package com.github.safetytrick.sqlexplorer.schema;

import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.github.safetytrick.sqlexplorer.core.TableRollup;
import com.github.safetytrick.sqlexplorer.service.SqlRepositoryService;
import static java.util.Comparator.comparing;

@Service
public class TableReportService {
    private final SqlMetadataService sqlMetadataService;
    private final SqlRepositoryService sqlRepositoryService;

    public TableReportService(SqlMetadataService sqlMetadataService, SqlRepositoryService sqlRepositoryService) {
        this.sqlMetadataService = sqlMetadataService;
        this.sqlRepositoryService = sqlRepositoryService;
    }

    public Stream<TableRollup> getTablesWithColumn(String columnName) {
        return sqlMetadataService.getTables().stream().filter(
            tableStructure -> tableStructure.columns().stream().anyMatch(column -> column.columnName().equalsIgnoreCase(columnName))
        ).map(table -> sqlRepositoryService.getTableRollup(table.tableReference()))
         .sorted(comparing(TableRollup::totalQueryTime)
                    .reversed());
    }
}
