package com.github.safetytrick.sqlexplorer.qualifier;

import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;

public interface TableAliasResolver {

    String resolveTableName(Column column);

    String resolveTableName(Table table);

    boolean hasAlias(String tableName);

    void addDerivedTable(DerivedTable derivedTable);
}
