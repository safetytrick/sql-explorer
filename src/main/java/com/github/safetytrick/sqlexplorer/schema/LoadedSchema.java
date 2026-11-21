package com.github.safetytrick.sqlexplorer.schema;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

public class LoadedSchema implements SqlSchemaRepository {

    private final List<TableStructure> tableStructures;
    private final Map<TableReference, TableStructure> byTableReference = new HashMap<>();
    private final ListMultimap<String, TableStructure> byTableName = ArrayListMultimap.create();
    private final CreateStatementLoader createStatementLoader;
    private final Map<TableStructure, String> createStatements = new ConcurrentHashMap<>();

    public LoadedSchema(List<TableStructure> tableStructures) {
        this(tableStructures, table -> {
            throw new UnsupportedOperationException("no create statement loader for this schema");
        });
    }

    public LoadedSchema(List<TableStructure> tableStructures, CreateStatementLoader createStatementLoader) {
        this.tableStructures = List.copyOf(tableStructures);
        this.createStatementLoader = createStatementLoader;
        for (TableStructure structure : this.tableStructures) {
            TableReference reference = structure.tableReference();
            byTableReference.put(reference.normalized(), structure);
            byTableName.put(reference.getNormalizedTableName(), structure);
        }
    }

    @Override
    public TableStructure getTable(TableReference table) {
        Objects.requireNonNull(table);

        return byTableReference.get(table.normalized());
    }

    @Override
    public List<TableStructure> getTables() {
        return tableStructures;
    }

    @Override
    public List<TableStructure> getTablesByName(TableReference table) {
        return byTableName.get(table.getNormalizedTableName());
    }

    @Override
    public String getCreateStatement(TableStructure table) {
        return createStatements.computeIfAbsent(table, createStatementLoader::load);
    }

    @FunctionalInterface
    public interface CreateStatementLoader {
        String load(TableStructure table);
    }
}
