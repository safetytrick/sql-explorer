package com.github.safetytrick.sqlexplorer.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.avro.TableReference;

import static java.util.stream.Collectors.groupingBy;

public interface SqlSchemaRepository {

    TableStructure getTable(TableReference table);

    List<TableStructure> getTables();

    List<TableStructure> getTablesByName(TableReference table);

    default String getCreateStatement(TableStructure table) {
        throw new UnsupportedOperationException("create statements are not available from " + getClass().getSimpleName());
    }

    default String findSchemaForTablesAndColumns(List<TableReference> tables, List<String> columns) {

        if (tables == null) {
            return null;
        }
        if (columns == null) {
            columns = Collections.emptyList();
        }

        List<String> schemaFromTableReferences = tables.stream().map(table -> {
            if (table.getSchemaName() != null) {
                return table.getSchemaName();
            } else {
                return "";
            }
        }).distinct().toList();

        if (schemaFromTableReferences.size() == 1 && !schemaFromTableReferences.contains("")) {
            return schemaFromTableReferences.getFirst();
        }

        List<TableStructure> allTables = new ArrayList<>();
        for (TableReference table : tables) {
            List<TableStructure> withDuplicates = getTablesByName(table);
            if (withDuplicates.size() == 1) {
                return withDuplicates.getFirst().schema();
            }
            allTables.addAll(withDuplicates);
        }

        Map<String, List<TableStructure>> bySchema = allTables.stream()
            .filter(x -> x.schema() != null)
            .collect(groupingBy(TableStructure::schema));

        for (Map.Entry<String, List<TableStructure>> entry : bySchema.entrySet()) {
            List<TableStructure> structures = entry.getValue();

            boolean allMatch = columns.stream().allMatch(column -> {
                String[] split = column.split("\\.", 2);

                String tableName;
                String columnName;
                if (split.length == 1) {
                    tableName = null;
                    columnName = column;
                } else if (split.length == 2) {
                    tableName = split[0];
                    columnName = split[1];
                } else {
                    throw new UnknownColumnException("could not find table for column: " + column);
                }

                return structures.stream().anyMatch(table -> (tableName == null || table.name().equalsIgnoreCase(tableName)) &&
                    table.columns().stream().anyMatch(cols -> cols.columnName().equalsIgnoreCase(columnName))
                );
            });

            if (allMatch) {
                return entry.getKey();
            }
        }

        return null;
    }

    class UnknownColumnException extends RuntimeException {
        public UnknownColumnException(String message) {
            super(message);
        }
    }
}
