package com.github.safetytrick.sqlexplorer.schema;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import org.springframework.stereotype.Service;

@Service
public class SqlSchemaService implements SqlSchemaRepository {

    private final SchemaLoadingService loader;

    public SqlSchemaService(SchemaLoadingService loader) {
        this.loader = loader;
    }

    @Override
    public TableStructure getTable(TableReference table) {
        return loader.getTable(table);
    }

    @Override
    public List<TableStructure> getTables() {
        return loader.getTables();
    }

    @Override
    public List<TableStructure> getTablesByName(TableReference table) {
        return loader.getAllByTableName(table.getNormalizedTableName());
    }
}
