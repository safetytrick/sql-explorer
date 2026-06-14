package com.github.safetytrick.sqlexplorer.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import net.sf.jsqlparser.statement.Statement;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Order(1000)
@Component
public class UnknownSchemaAnalyzer implements SqlAnalyzer {

    @Override
    public void analyze(Statement statement, SqlProfile.Builder builder, SqlSchemaRepository schemaRepository) {
        if (schemaRepository != null && builder.getSchemaName() == null) {
            String schema = deriveSchemaFrom(schemaRepository, builder.getTables(), builder.getJoinColumns(), builder.getCriteriaColumns());
            builder.setSchemaName(schema);
        }
    }

    private String deriveSchemaFrom(SqlSchemaRepository schemaRepository, List<TableReference> tableNames, List<JoinColumns> joinColumns, List<ColumnReference> criteriaColumns) {
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

        return schemaRepository.findSchemaForTablesAndColumns(tableNames, allColumns);
    }
}
