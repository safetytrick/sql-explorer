package com.github.safetytrick.sqlexplorer.core;

import java.util.Objects;

import com.github.safetytrick.sqlexplorer.cleanup.NameNormalizer;
import com.fasterxml.jackson.annotation.JsonIgnore;

public class TableReference {
    private final String schema;
    private final String normalizedSchema;
    private final String tableName;
    private final String normalizedTableName;

    public TableReference(String schema, String tableName) {
        if (tableName == null) {
            tableName = "dual";
        }
        this.schema = schema;
        this.normalizedSchema = NameNormalizer.normalize(schema);
        this.tableName = tableName;
        this.normalizedTableName = NameNormalizer.normalize(tableName);
    }

    public String getSchema() {
        return schema;
    }

    public String getTableName() {
        return tableName;
    }

    @JsonIgnore
    public String getNormalizedSchema() {
        return normalizedSchema;
    }

    @JsonIgnore
    public String getNormalizedTableName() {
        return normalizedTableName;
    }

    @JsonIgnore
    public String getNormalizedName() {
        return schema == null ? normalizedTableName : normalizedSchema + "." + normalizedTableName;
    }


    @JsonIgnore
    public String getFullName() {
        return schema == null ? tableName : schema + "." + tableName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TableReference that = (TableReference) o;
        return Objects.equals(normalizedSchema, that.normalizedSchema) &&
            Objects.equals(normalizedTableName, that.normalizedTableName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(normalizedSchema, normalizedTableName);
    }

    public TableReference withDefaultSchema(String schema) {
        if (this.schema == null) {
            return new TableReference(schema, tableName);
        }
        return this;
    }

    public static TableReference fromString(String reference) {
        String[] split = reference.split("\\.");
        if (split.length == 1) {
            return new TableReference(null, split[0]);
        } else if (split.length == 2) {
            return new TableReference(split[0], split[1]);
        } else {
            throw new IllegalArgumentException("Invalid reference: " + reference);
        }
    }

    @Override
    public String toString() {
        return getNormalizedName();
    }
}
