package com.github.safetytrick.sqlexplorer.schema;

import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryService;
import com.github.safetytrick.sqlexplorer.model.TableRollup;
import org.springframework.stereotype.Service;

import static java.util.Comparator.comparing;

@Service
class TableReportService {
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
