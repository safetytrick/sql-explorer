package com.github.safetytrick.sqlexplorer.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.model.ColumnReference;
import com.github.safetytrick.sqlexplorer.model.JoinColumns;
import com.github.safetytrick.sqlexplorer.model.SqlProfile;
import com.github.safetytrick.sqlexplorer.model.TableReference;
import com.github.safetytrick.sqlexplorer.schema.SqlMetadataService;
import jakarta.inject.Inject;
import net.sf.jsqlparser.statement.Statement;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Order(1000)
@Component
public class UnknownSchemaAnalyzer implements SqlAnalyzer {
    private final SqlMetadataService sqlMetadataService;

    @Inject
    public UnknownSchemaAnalyzer(SqlMetadataService sqlMetadataService) {
        this.sqlMetadataService = sqlMetadataService;
    }

    @Override
    public void analyze(Statement statement, SqlProfile.Builder builder) {
        if (builder.view().getSchema() == null) {
            String schema = deriveSchemaFrom(builder.view().getTables(), builder.view().getJoinColumns(), builder.view().getCriteriaColumns());
            builder.schema(schema);
        }
    }

    private String deriveSchemaFrom(List<TableReference> tableNames, List<JoinColumns> joinColumns, List<ColumnReference> criteriaColumns) {
        List<String> allColumns = new ArrayList<>();
        if (joinColumns != null) {
            joinColumns.stream()
                .flatMap(join -> Stream.of(join.right().getColumn(), join.left().getColumn()))
                .forEach(allColumns::add);
        }
        if (criteriaColumns != null) {
            criteriaColumns.stream()
                .map(ColumnReference::getNormalizedColumn)
                .forEach(allColumns::add);
        }

        return sqlMetadataService.findSchemaForTablesAndColumns(tableNames, allColumns);
    }
}
