package com.github.safetytrick.sqlexplorer.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaService;
import jakarta.inject.Inject;
import net.sf.jsqlparser.statement.Statement;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Order(1000)
@Component
public class UnknownSchemaAnalyzer implements SqlAnalyzer {
    private final SqlSchemaService sqlSchemaService;

    @Inject
    public UnknownSchemaAnalyzer(SqlSchemaService sqlSchemaService) {
        this.sqlSchemaService = sqlSchemaService;
    }

    @Override
    public void analyze(Statement statement, SqlProfile.Builder builder) {
        if (builder.getSchemaName() == null) {
            String schema = deriveSchemaFrom(builder.getTables(), builder.getJoinColumns(), builder.getCriteriaColumns());
            builder.setSchemaName(schema);
        }
    }

    private String deriveSchemaFrom(List<TableReference> tableNames, List<JoinColumns> joinColumns, List<ColumnReference> criteriaColumns) {
        List<String> allColumns = new ArrayList<>();
        if (joinColumns != null) {
            joinColumns.stream()
                .flatMap(join -> Stream.of(join.getRight().getNormalizedColumn(), join.getLeft().getNormalizedColumn()))
                .forEach(allColumns::add);
        }
        if (criteriaColumns != null) {
            criteriaColumns.stream()
                .map(ColumnReference::getNormalizedColumn)
                .forEach(allColumns::add);
        }

        return sqlSchemaService.findSchemaForTablesAndColumns(tableNames, allColumns);
    }
}
