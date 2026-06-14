package com.github.safetytrick.sqlexplorer.analysis.resolver;

import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import org.springframework.stereotype.Component;

@Component
public class DefaultColumnTableResolverFactory implements ColumnTableResolverFactory {

    @Override
    public ColumnTableResolver forSchema(SqlSchemaRepository schema) {
        return schema == null ? ColumnTableResolver.unsupported() : new ColumnTableResolver(schema);
    }
}
