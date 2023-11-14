package com.github.safetytrick.sqlexplorer.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

public class TableIndex {
    private final String schema;
    private final boolean unique;
    private final String identifier;
    private final String name;
    private final IndexType indexType;
    private final IndexDirection direction;
    private final long cardinality;
    private final long pages;
    private final String filterCondition;
    private final List<String> columns;

    public String getSchema() {
        return schema;
    }

    public boolean isUnique() {
        return unique;
    }

    public String getIdentifier() {
        return identifier;
    }

    public String getName() {
        return name;
    }

    public IndexType getIndexType() {
        return indexType;
    }

    public IndexDirection getDirection() {
        return direction;
    }

    public long getCardinality() {
        return cardinality;
    }

    public long getPages() {
        return pages;
    }

    public String getFilterCondition() {
        return filterCondition;
    }

    public List<String> getColumns() {
        return columns;
    }

    private TableIndex(String schema,
                       boolean unique,
                       String identifier,
                       String name,
                       IndexType indexType,
                       IndexDirection direction,
                       long cardinality,
                       long pages,
                       String filterCondition,
                       List<String> columns) {
        this.schema = schema;
        this.unique = unique;
        this.identifier = identifier;
        this.name = name;
        this.indexType = indexType;
        this.direction = direction;
        this.cardinality = cardinality;
        this.pages = pages;
        this.filterCondition = filterCondition;
        this.columns = columns;
    }

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
        private NavigableMap<Integer, String> columns = new TreeMap<>();

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
                                  Collections.unmodifiableList(new ArrayList<>(columns.values())));
        }

        public Builder addColumn(short ordinalPosition, String columnName) {
            columns.put((int) ordinalPosition, columnName);
            return this;
        }
    }
}

