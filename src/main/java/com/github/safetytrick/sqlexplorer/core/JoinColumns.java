package com.github.safetytrick.sqlexplorer.core;

public record JoinColumns(ColumnReference left, ColumnReference right) {

    public static JoinColumns fromReferences(String left, String right) {
        return new JoinColumns(ColumnReference.fromString(left),
            ColumnReference.fromString(right));
    }

    public boolean contains(ColumnReference columnReference) {
        return left.equals(columnReference) || right.equals(columnReference);
    }


    public JoinColumns withDefaultSchema(String schema) {
        if (left.getTable().getSchema() == null || right.getTable().getSchema() == null) {
            return new JoinColumns(left.withDefaultSchema(schema), right.withDefaultSchema(schema));
        }
        return this;
    }

    @Override
    public String toString() {
        return left + "=" + right;
    }
}
