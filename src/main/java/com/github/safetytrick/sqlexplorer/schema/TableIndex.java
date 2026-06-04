package com.github.safetytrick.sqlexplorer.schema;

import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

public record TableIndex(String schema,
                         boolean unique,
                         String identifier,
                         String name,
                         IndexType indexType,
                         IndexDirection direction,
                         long cardinality,
                         long pages,
                         String filterCondition,
                         List<String> columns) {

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String schema;
        private boolean unique;
        private String identifier;
        private String name;
        private IndexType indexType;
        private IndexDirection direction;
        private long cardinality;
        private long pages;
        private String filterCondition;
        private final NavigableMap<Integer, String> columns = new TreeMap<>();

        public Builder schema(String schema) {
            this.schema = schema;
            return this;
        }

        public Builder unique(boolean unique) {
            this.unique = unique;
            return this;
        }

        public Builder identifier(String identifier) {
            this.identifier = identifier;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder indexType(IndexType indexType) {
            this.indexType = indexType;
            return this;
        }

        public Builder direction(IndexDirection direction) {
            this.direction = direction;
            return this;
        }

        public Builder cardinality(long cardinality) {
            this.cardinality = cardinality;
            return this;
        }

        public Builder pages(long pages) {
            this.pages = pages;
            return this;
        }

        public Builder filterCondition(String filterCondition) {
            this.filterCondition = filterCondition;
            return this;
        }

        public TableIndex build() {
            return new TableIndex(schema, unique, identifier, name, indexType, direction, cardinality, pages, filterCondition,
                List.copyOf(columns.values().stream().filter(Objects::nonNull).toList()));
        }

        public Builder addColumn(short ordinalPosition, String columnName) {
            columns.put((int) ordinalPosition, columnName);
            return this;
        }
    }
}

