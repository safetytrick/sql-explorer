package com.github.safetytrick.sqlexplorer.schema;

public class GroupedTableRelationships extends TableRelationships {
    private final String group;

    public GroupedTableRelationships(String group, TableRelationships tableRelationships) {
        super(tableRelationships.getTable(), tableRelationships.getJoinTables(), tableRelationships.getRelatedTables());

        this.group = group;
    }

    public String getGroup() {
        return group;
    }
}
