package com.github.safetytrick.sqlexplorer.core;

import javax.annotation.Nullable;
import java.util.Objects;

import com.github.safetytrick.sqlexplorer.cleanup.NameNormalizer;
import com.fasterxml.jackson.annotation.JsonIgnore;

public class ColumnReference {
    private final TableReference table;
    private final String column;
    private final String columnLowerCase;

    public ColumnReference(TableReference table, String column) {
        this.table = Objects.requireNonNull(table);
        this.column = Objects.requireNonNull(column);
        this.columnLowerCase = NameNormalizer.normalize(column);
    }

    /**
     *
     * @param reference Either a two or three part column reference, table.column, or schema.table.column
     * @return a ColumnReference object from components
     * @throws IllegalArgumentException if the string is not a two or three part reference
     */
    public static ColumnReference fromString(String reference) {
        String[] split = reference.split("\\.");
        if (split.length == 2) {
            return ColumnReference.fromComponents(null, split[0], split[1]);
        } else if (split.length == 3) {
            return ColumnReference.fromComponents(split[0], split[1], split[2]);
        } else {
            throw new IllegalArgumentException("invalid column reference: " + reference);
        }
    }

    public static ColumnReference fromComponents(@Nullable String schema, String table, String column) {
        return new ColumnReference(new TableReference(schema, table), column);
    }

    public TableReference getTable() {
        return table;
    }

    public String getColumn() {
        return column;
    }

    @JsonIgnore
    public String getNormalizedColumn() {
        return columnLowerCase;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ColumnReference that = (ColumnReference) o;
        return Objects.equals(table, that.table) && Objects.equals(columnLowerCase, that.columnLowerCase);
    }

    @Override
    public int hashCode() {
        return Objects.hash(table, columnLowerCase);
    }

    public String getNormalizedReference() {
        return table.getNormalizedName() + "." + getNormalizedColumn();
    }

    public ColumnReference withDefaultSchema(String schema) {
        if (table.getSchema() == null) {
            return new ColumnReference(table.withDefaultSchema(schema), column);
        }
        return this;
    }

    @Override
    public String toString() {
        return getNormalizedReference();
    }
}
