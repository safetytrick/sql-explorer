package com.github.safetytrick.sqlexplorer.schema;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;
import org.springframework.stereotype.Service;

@Service
public class SqlSchemaService implements SqlSchemaRepository {

    private final EnvironmentContext environmentContext;

    public SqlSchemaService(EnvironmentContext environmentContext) {
        this.environmentContext = environmentContext;
    }

    private SqlSchemaRepository activeSchema() {
        return environmentContext.current().schemaRepository();
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
