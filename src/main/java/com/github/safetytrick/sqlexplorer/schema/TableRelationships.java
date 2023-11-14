package com.github.safetytrick.sqlexplorer.schema;

import java.util.List;

import com.github.safetytrick.sqlexplorer.core.TableReference;

public class TableRelationships {

    private final TableReference table;
    private final List<TableReference> joinTables;
    private final List<TableReference> relatedTables;

    public TableRelationships(TableReference table, List<TableReference> joinTables, List<TableReference> relatedTables) {
        this.table = table;
        this.joinTables = joinTables;
        this.relatedTables = relatedTables;
    }

    public TableReference getTable() {
        return table;
    }

    public List<TableReference> getJoinTables() {
        return joinTables;
    }

    public List<TableReference> getRelatedTables() {
        return relatedTables;
    }
}
