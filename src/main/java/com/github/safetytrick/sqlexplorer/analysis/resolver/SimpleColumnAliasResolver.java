package com.github.safetytrick.sqlexplorer.analysis.resolver;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.schema.Column;

public class SimpleColumnAliasResolver extends ExpressionVisitorAdapter<Void> implements ColumnAliasResolver {
    private String columnReference;
    private final TableAliasResolver aliasResolver;

    public SimpleColumnAliasResolver(TableAliasResolver aliasResolver) {
        this.aliasResolver = aliasResolver;
    }

    @Override
    public String columnReference(Expression expression) {
        columnReference = null;
        expression.accept(this);
        return columnReference;
    }

    @Override
    public <S> Void visit(Column column, S context) {
        columnReference = columnReference(column);
        return null;
    }

    @Override
    public String columnReference(Column column) {
        String tableName = aliasResolver.resolveTableName(column);

        return tableName == null ? null : tableName + "." + column.getColumnName();

    }
}
