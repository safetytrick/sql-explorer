package com.github.safetytrick.sqlexplorer.schema;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

/**
 * The schema loaded from one database server: immutable after construction
 * apart from the lazily populated create statement cache.
 *
 * <p>Create statements are loaded on demand because running
 * {@code show create table} per table is slow (relatively) and only needed
 * when the user opens the "Create View" for a single table. The cache is keyed
 * on the {@link TableStructure}, so a reload that produces a structurally
 * different table naturally misses and reloads, while an unchanged structure
 * keeps its cached statement.
 */
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
            // key on the normalized name so lookups (which normalize) match
            // regardless of backticks/casing in the source name.
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
