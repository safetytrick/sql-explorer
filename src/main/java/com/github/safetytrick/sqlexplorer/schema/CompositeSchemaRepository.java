package com.github.safetytrick.sqlexplorer.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.TableReference;

public class CompositeSchemaRepository implements SqlSchemaRepository {

    private final List<SqlSchemaRepository> schemas;

    public CompositeSchemaRepository(List<SqlSchemaRepository> schemas) {
        this.schemas = List.copyOf(schemas);
    }

    @Override
    public TableStructure getTable(TableReference table) {
        TableStructure first = null;
        for (SqlSchemaRepository schema : schemas) {
            TableStructure structure = schema.getTable(table);
            if (structure != null) {
                first = first == null ? structure : withStatsOf(first, structure);
            }
        }
        return first;
    }

    private static TableStructure withStatsOf(TableStructure first, TableStructure other) {
        if (other.stats() == null || other.stats().isEmpty()) {
            return first;
        }
        List<TableStats> stats = new ArrayList<>(first.stats() == null ? List.of() : first.stats());
        stats.addAll(other.stats());
        return first.withStats(stats);
    }

    @Override
    public List<TableStructure> getTables() {
        return firstOfEach(schemas.stream().flatMap(schema -> schema.getTables().stream()));
    }

    @Override
    public List<TableStructure> getTablesByName(TableReference table) {
        return firstOfEach(schemas.stream().flatMap(schema -> schema.getTablesByName(table).stream()));
    }

    private static List<TableStructure> firstOfEach(Stream<TableStructure> tables) {
        Map<TableReference, TableStructure> first = new LinkedHashMap<>();
        tables.forEach(structure -> first.merge(structure.tableReference().normalized(), structure,
            CompositeSchemaRepository::withStatsOf));
        return List.copyOf(first.values());
    }

    @Override
    public String getCreateStatement(TableStructure table) {
        for (SqlSchemaRepository schema : schemas) {
            if (schema.getTable(table.tableReference()) != null) {
                return schema.getCreateStatement(table);
            }
        }
        return null;
    }
}
