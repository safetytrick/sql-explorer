package com.github.safetytrick.sqlexplorer.analysis.resolver;

import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;

@FunctionalInterface
public interface ColumnTableResolverFactory {

    ColumnTableResolver forSchema(SqlSchemaRepository schema);
}
