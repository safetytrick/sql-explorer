package com.github.safetytrick.sqlexplorer.schema;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import org.springframework.stereotype.Service;

/**
 * The schema of the active environment. Phase 1 of the environments work:
 * always the default environment; phases 2-3 replace this delegate with the
 * schema repository resolved per request/import (see docs/DATA_ENVIRONMENTS.md).
 */
@Service
public class SqlSchemaService implements SqlSchemaRepository {

    private final EnvironmentRegistry environmentRegistry;

    public SqlSchemaService(EnvironmentRegistry environmentRegistry) {
        this.environmentRegistry = environmentRegistry;
    }

    private SqlSchemaRepository activeSchema() {
        return environmentRegistry.getDefault().schemaRepository();
    }

    @Override
    public TableStructure getTable(TableReference table) {
        return activeSchema().getTable(table);
    }

    @Override
    public List<TableStructure> getTables() {
        return activeSchema().getTables();
    }

    @Override
    public List<TableStructure> getTablesByName(TableReference table) {
        return activeSchema().getTablesByName(table);
    }

    @Override
    public String getCreateStatement(TableStructure table) {
        return activeSchema().getCreateStatement(table);
    }
}
