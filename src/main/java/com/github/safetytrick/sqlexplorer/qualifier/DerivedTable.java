package com.github.safetytrick.sqlexplorer.qualifier;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Column;

public record DerivedTable(String alias, ColumnAliasResolver columnAliasResolver) implements ColumnAliasResolver {

    @Override
    public String columnReference(Expression expression) {
        return columnAliasResolver.columnReference(expression);
    }

    @Override
    public String columnReference(Column column) {
        return columnAliasResolver.columnReference(column);
    }
}
