package com.github.safetytrick.sqlexplorer.model;

import java.util.Comparator;
import java.util.List;

import com.github.safetytrick.sqlexplorer.analysis.IgnoredSchemas;
import com.github.safetytrick.sqlexplorer.commentary.Tag;

public record TableRollup(String name,
                         int numberOfQueries,
                         double totalQueryTime,
                         String schema,
                         List<Tag> tags) {

    public static Comparator<TableRollup> comparator(IgnoredSchemas ignoredSchemas) {
        return (o1, o2) -> {
            int timeDiff = Double.compare(o2.totalQueryTime(), o1.totalQueryTime());
            if (ignoredSchemas.contains(o1.schema())) {
                if (ignoredSchemas.contains(o2.schema())) {
                    return timeDiff;
                }
                return 1;
            } else  if (ignoredSchemas.contains(o2.schema())) {
                return -1;
            }
            return timeDiff;
        };
    }
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

        public Builder totalQueryTime(double totalQueryTime) {
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
