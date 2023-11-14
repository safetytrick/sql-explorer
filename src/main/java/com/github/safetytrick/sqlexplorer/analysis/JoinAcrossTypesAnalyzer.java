package com.github.safetytrick.sqlexplorer.analysis;

import java.sql.JDBCType;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.github.safetytrick.sqlexplorer.core.ColumnReference;
import com.github.safetytrick.sqlexplorer.core.JoinColumns;
import com.github.safetytrick.sqlexplorer.schema.SqlMetadataService;
import com.github.safetytrick.sqlexplorer.schema.TableColumn;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// todo this misses column comparison in where clause
@Component
public class JoinAcrossTypesAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(JoinAcrossTypesAnalyzer.class);

    private final SqlMetadataService sqlMetadataService;

    public JoinAcrossTypesAnalyzer(SqlMetadataService sqlMetadataService) {
        this.sqlMetadataService = sqlMetadataService;
    }

    public List<JoinColumns> mismatchedJoins(String schema, List<JoinColumns> joinColumns) {
        if (joinColumns == null) {
            return Collections.emptyList();
        }
        return joinColumns.stream()
            .map(joinPair -> {
                if (doColumnTypesMatch(schema, joinPair.getLeft(), joinPair.getRight())) {
                    return null;
                }
                return joinPair;
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    private boolean doColumnTypesMatch(String schema, ColumnReference left, ColumnReference right) {
        TableColumn leftColumn = getColumnFromReference(schema, left).orElse(null);
        TableColumn rightColumn = getColumnFromReference(schema, right).orElse(null);
        if (leftColumn == null || rightColumn == null) {
            // todo report this as a general issue with the environment instead of just debug logging
            log.debug("Comparison may be incorrect, column definition not found: {}={} {}={}", left, leftColumn, right, rightColumn);
            return false;
        }

        if (!Objects.equals(leftColumn.getCharacterSet(), rightColumn.getCharacterSet())) {
            return false;
        }

        return leftColumn.getType() == rightColumn.getType() ||
            leftColumn.getType() == JDBCType.CHAR && rightColumn.getType() == JDBCType.VARCHAR ||
            leftColumn.getType() == JDBCType.VARCHAR && rightColumn.getType() == JDBCType.CHAR || haveCompatibleIntegerTypes(leftColumn.getType(), rightColumn.getType());
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

    private Optional<TableColumn> getColumnFromReference(String schema, ColumnReference reference) {

        TableStructure table = sqlMetadataService.getTable(reference.getTable().withDefaultSchema(schema));
        if (table == null) {
            return Optional.empty();
        }
        return table.getColumns().stream().filter(x -> x.getColumnName().equalsIgnoreCase(reference.getNormalizedColumn()))
            .findFirst();
    }
}
