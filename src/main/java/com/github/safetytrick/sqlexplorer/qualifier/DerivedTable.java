package com.github.safetytrick.sqlexplorer.qualifier;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Column;

public class DerivedTable implements ColumnAliasResolver {

    private final String alias;
    private final ColumnAliasResolver columnAliasResolver;

    public DerivedTable(String alias, ColumnAliasResolver columnAliasResolver) {
        this.alias = alias;
        this.columnAliasResolver = columnAliasResolver;
    }

    public String getAlias() {
        return alias;
    }

    public ColumnAliasResolver getColumnAliasResolver() {
        return columnAliasResolver;
    }

    @Override
    public String columnReference(Expression expression) {
        return columnAliasResolver.columnReference(expression);
    }

    @Override
    public String columnReference(Column column) {
        return columnAliasResolver.columnReference(column);
    }
}
