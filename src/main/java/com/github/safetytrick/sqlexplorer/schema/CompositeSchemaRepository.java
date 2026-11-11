package com.github.safetytrick.sqlexplorer.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.TableReference;

/**
 * Combines the schemas of an environment's servers. Lookups and listings both
 * answer with the first server's definition of a table; a schema whose tables
 * genuinely differ between two servers in one environment is unsupported (see
 * docs/DATA_ENVIRONMENTS.md, open questions).
 */
public class CompositeSchemaRepository implements SqlSchemaRepository {

    private final List<SqlSchemaRepository> schemas;

    public CompositeSchemaRepository(List<SqlSchemaRepository> schemas) {
        this.schemas = List.copyOf(schemas);
    }

    /**
     * The first server's definition, carrying every server's stats for the
     * table: rows and size are per shard, and the shards together are the
     * table (sqlx-n5j.2).
     */
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

    /** {@code first} with {@code other}'s stats added to its own; unchanged when other has none. */
    private static TableStructure withStatsOf(TableStructure first, TableStructure other) {
        if (other.stats() == null || other.stats().isEmpty()) {
            return first;
        }
        List<TableStats> stats = new ArrayList<>(first.stats() == null ? List.of() : first.stats());
        stats.addAll(other.stats());
        return first.withStats(stats);
    }

    /**
     * Every table once, as the first server to define it did — the same answer
     * {@link #getTable} gives. An environment's servers are usually a primary
     * and its replicas carrying one schema, and listing a table per server put
     * seventeen copies of it into every caller on production (sqlx-3js).
     */
    @Override
    public List<TableStructure> getTables() {
        return firstOfEach(schemas.stream().flatMap(schema -> schema.getTables().stream()));
    }

    /**
     * The same name in two schemas is still two answers — schema inference
     * reads that ambiguity — but the same table on two servers is one.
     */
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
