package com.github.safetytrick.sqlexplorer.analysis.resolver;

import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;

/**
 * Builds a {@link ColumnTableResolver} bound to a specific schema. Lets the
 * analyzers resolve columns against the schema of the server the SQL was
 * captured on (passed per analysis call) rather than a global singleton.
 */
@FunctionalInterface
public interface ColumnTableResolverFactory {

    ColumnTableResolver forSchema(SqlSchemaRepository schema);
}
