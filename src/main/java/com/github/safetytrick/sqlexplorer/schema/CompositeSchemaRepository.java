package com.github.safetytrick.sqlexplorer.schema;

import java.util.List;
import java.util.Objects;

import com.github.safetytrick.sqlexplorer.avro.TableReference;

/**
 * Combines the schemas of an environment's servers. Lookups return the first
 * match; same-named schemas on two servers in one environment are unsupported
 * (see docs/DATA_ENVIRONMENTS.md, open questions).
 */
public class CompositeSchemaRepository implements SqlSchemaRepository {

    private final List<SqlSchemaRepository> schemas;

    public CompositeSchemaRepository(List<SqlSchemaRepository> schemas) {
        this.schemas = List.copyOf(schemas);
    }

    @Override
    public TableStructure getTable(TableReference table) {
        return schemas.stream()
            .map(schema -> schema.getTable(table))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    @Override
    public List<TableStructure> getTables() {
        return schemas.stream()
            .flatMap(schema -> schema.getTables().stream())
            .toList();
    }

    @Override
    public List<TableStructure> getTablesByName(TableReference table) {
        return schemas.stream()
            .flatMap(schema -> schema.getTablesByName(table).stream())
            .toList();
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
