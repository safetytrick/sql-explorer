package com.github.safetytrick.sqlexplorer.qualifier;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Column;

public interface ColumnAliasResolver {
    String columnReference(Expression expression);

    String columnReference(Column column);
}
