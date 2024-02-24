package com.github.safetytrick.sqlexplorer.analysis;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.github.safetytrick.sqlexplorer.analysis.exception.UnsupportedSqlException;
import com.github.safetytrick.sqlexplorer.core.TableReference;
import com.github.safetytrick.sqlexplorer.schema.SqlMetadataService;
import com.github.safetytrick.sqlexplorer.schema.TableColumn;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import org.springframework.stereotype.Component;

@Component
public class ColumnTableResolver {

    private final SqlMetadataService sqlMetadataService;

    public ColumnTableResolver(SqlMetadataService sqlMetadataService) {
        this.sqlMetadataService = sqlMetadataService;
    }

    public static ColumnTableResolver unsupported() {
        return new UnsupportedColumnTableResolver();
    }

    public String selectTableNameFromCandidates(List<String> tables, String column) {
        String schema = sqlMetadataService.findSchemaForTablesAndColumns(tables.stream().map(TableReference::fromString).collect(Collectors.toList()), Collections.singletonList(column));
        return selectTableNameFromCandidates(schema, tables, column);
    }

    // todo pass a better schema to this method
    protected String selectTableNameFromCandidates(String schema, List<String> tables, String column) {
        if (schema == null) {
            throw new UnsupportedSqlException("missing schema");
        }
        Set<TableReference> matchedTables = new HashSet<>(1);
        String missingTable = null;
        for (String table : tables) {
            // todo handle schema on table reference
            TableReference tableReference = TableReference.fromString(table);
            TableStructure tableStructure = sqlMetadataService.getTable(tableReference.withDefaultSchema(schema));
            if (tableStructure == null) {
                missingTable = table;
                continue;
            }
            tableStructure.columns().stream()
                .filter(x -> x.columnName().equalsIgnoreCase(column))
                .findFirst().ifPresent(x -> matchedTables.add(tableReference));

        }

        if (matchedTables.size() == 1) {
            return matchedTables.iterator().next().getFullName();
        }
        if (matchedTables.size() > 1) {
            throw new UnsupportedSqlException("multiple tables found for column: " + column + " tables: " + matchedTables + " in schema: " + schema);
        }

        if (missingTable != null) {
            throw new UnsupportedSqlException("could not find table: " + missingTable + " in schema: " + schema);
        }
        return null;
    }

    public List<String> getAllColumnNamesForTable(String schema, String table) {
        return sqlMetadataService.getTable(new TableReference(schema, table))
            .columns()
            .stream()
            .map(TableColumn::columnName)
            .collect(Collectors.toList());
    }

    private static class UnsupportedColumnTableResolver extends ColumnTableResolver {

        public UnsupportedColumnTableResolver() {
            super(null);
        }

        @Override
        public String selectTableNameFromCandidates(List<String> tables, String column) {
            throw new UnsupportedSqlException("no effort made");
        }

        @Override
        public String selectTableNameFromCandidates(String schema, List<String> tables, String column) {
            throw new UnsupportedSqlException("no effort made");
        }
    }
}
