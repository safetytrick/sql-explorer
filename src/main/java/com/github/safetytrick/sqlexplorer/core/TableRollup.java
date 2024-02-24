package com.github.safetytrick.sqlexplorer.core;

import java.util.List;

import com.github.safetytrick.sqlexplorer.anno.Tag;

public record TableRollup(String name,
                         int numberOfQueries,
                         double totalQueryTime,
                         String schema,
                         List<Tag> tags) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private int numberOfQueries;
        private double totalQueryTime;
        private String schema;
        private List<Tag> tags;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder numberOfQueries(int numberOfQueries) {
            this.numberOfQueries = numberOfQueries;
            return this;
        }

        public Builder sumOfQueryTime(double totalQueryTime) {
            this.totalQueryTime = totalQueryTime;
            return this;
        }

        public Builder tags(List<Tag> tags) {
            this.tags = tags;
            return this;
        }

        public TableRollup build() {
            return new TableRollup(name, numberOfQueries, totalQueryTime, schema, tags);
        }

        public Builder schema(String schema) {
            this.schema = schema;
            return this;
        }
    }
}
