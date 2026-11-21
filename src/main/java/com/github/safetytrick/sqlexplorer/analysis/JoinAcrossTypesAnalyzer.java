package com.github.safetytrick.sqlexplorer.analysis;

import java.sql.JDBCType;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import com.github.safetytrick.sqlexplorer.schema.TableColumn;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import net.sf.jsqlparser.statement.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Order(Ordered.LOWEST_PRECEDENCE)
@Component
public class JoinAcrossTypesAnalyzer implements SqlAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(JoinAcrossTypesAnalyzer.class);

    @Override
    public void analyze(Statement statement, SqlProfile.Builder builder, SqlSchemaRepository schemaRepository) {
        List<JoinColumns> mismatchedJoins = mismatchedJoins(schemaRepository, builder.getSchemaName(), builder.getJoinColumns());
        builder.setMismatchedJoins(mismatchedJoins);
    }

    public List<JoinColumns> mismatchedJoins(SqlSchemaRepository schemaRepository, String schema, List<JoinColumns> joinColumns) {
        if (schemaRepository == null || joinColumns == null) {
            return Collections.emptyList();
        }
        return joinColumns.stream()
            .map(joinPair -> {
                if (doColumnTypesMatch(schemaRepository, schema, joinPair.getLeft(), joinPair.getRight())) {
                    return null;
                }
                return joinPair;
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    private boolean doColumnTypesMatch(SqlSchemaRepository schemaRepository, String schema, ColumnReference left, ColumnReference right) {
        TableColumn leftColumn = getColumnFromReference(schemaRepository, schema, left).orElse(null);
        TableColumn rightColumn = getColumnFromReference(schemaRepository, schema, right).orElse(null);
        if (leftColumn == null || rightColumn == null) {
            log.debug("Comparison may be incorrect, column definition not found: {}={} {}={}", left, leftColumn, right, rightColumn);
            return false;
        }

        if (!Objects.equals(leftColumn.characterSet(), rightColumn.characterSet())) {
            return false;
        }

        return leftColumn.type() == rightColumn.type() ||
            leftColumn.type() == JDBCType.CHAR && rightColumn.type() == JDBCType.VARCHAR ||
            leftColumn.type() == JDBCType.VARCHAR && rightColumn.type() == JDBCType.CHAR || haveCompatibleIntegerTypes(leftColumn.type(), rightColumn.type());
    }

    private boolean haveCompatibleIntegerTypes(JDBCType left, JDBCType right) {
        switch (left) {
            case TINYINT:
            case SMALLINT:
            case INTEGER:
            case BIGINT:
                switch (right) {
                    case TINYINT:
                    case SMALLINT:
                    case INTEGER:
                    case BIGINT:
                        return true;
                }
            default:
        }
        return false;
    }

    private Optional<TableColumn> getColumnFromReference(SqlSchemaRepository schemaRepository, String schema, ColumnReference reference) {

        TableStructure table = schemaRepository.getTable(reference.getTable().withDefaultSchema(schema));
        if (table == null) {
            return Optional.empty();
        }
        return table.columns().stream().filter(x -> x.columnName().equalsIgnoreCase(reference.getNormalizedColumn()))
            .findFirst();
    }
}
