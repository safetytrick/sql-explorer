package com.github.safetytrick.sqlexplorer.graph;

import java.util.List;

import com.github.safetytrick.sqlexplorer.core.TableReference;

public record GroupedTableRelationships(
    String group,
    TableReference table,
    List<TableReference> foreignKeyTables,
    List<TableReference> joinTables,
    List<TableReference> relatedTables
) {
    public GroupedTableRelationships(String group, TableRelationships relationships) {
        this(group, relationships.table(), relationships.foreignKeyTables(), relationships.joinTables(), relationships.relatedTables());
    }
}
