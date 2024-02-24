package com.github.safetytrick.sqlexplorer.schema;

import java.util.List;

import com.github.safetytrick.sqlexplorer.core.TableReference;

public record GroupedTableRelationships(
    String group,
    TableReference table,
    List<TableReference> foreignKeys,
    List<TableReference> joinTables,
    List<TableReference> relatedTables
) {
    public GroupedTableRelationships(String group, TableRelationships relationships) {
        this(group, relationships.table(), relationships.foreignKeys(), relationships.joinTables(), relationships.relatedTables());
    }
}
