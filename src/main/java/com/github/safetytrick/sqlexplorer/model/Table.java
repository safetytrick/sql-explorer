package com.github.safetytrick.sqlexplorer.model;

import java.util.List;

import com.github.safetytrick.sqlexplorer.schema.TableStructure;

public record Table(String name,
                   int numberOfQueries,
                   double totalQueryTime,
                   List<SqlProfile> queries,
                   TableStructure structure,
                   String schema,
                   List<TableReference> foreignKeyTables,
                   List<TableReference> joinTables,
                   List<TableReference> relatedTables) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private int numberOfQueries;
        private double totalQueryTime;
        private List<SqlProfile> queries;
        private TableStructure structure;
        private String schema;
        private List<TableReference> foreignKeyTables;
        private List<TableReference> joinTables;
        private List<TableReference> relatedTables;

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

        public Builder queries(List<SqlProfile> queries) {
            this.queries = queries;
            return this;
        }

        public Builder structure(TableStructure structure) {
            this.structure = structure;
            return this;
        }

        public Builder foreignKeyTables(List<TableReference> foreignKeyTables) {
            this.foreignKeyTables = foreignKeyTables;
            return this;
        }

        public Builder joinTables(List<TableReference> joinTables) {
            this.joinTables = joinTables;
            return this;
        }

        public Builder relatedTables(List<TableReference> relatedTables) {
            this.relatedTables = relatedTables;
            return this;
        }

        public Builder schema(String schema) {
            this.schema = schema;
            return this;
        }

        public Table build() {
            return new Table(name, numberOfQueries, totalQueryTime, queries, structure, schema, foreignKeyTables, joinTables, relatedTables);
        }
    }
}
