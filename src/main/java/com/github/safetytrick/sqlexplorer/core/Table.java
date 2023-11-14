package com.github.safetytrick.sqlexplorer.core;

import java.util.List;

import com.github.safetytrick.sqlexplorer.schema.TableStructure;

public class Table {
    private final String name;
    private final int numberOfQueries;
    private final double totalQueryTime;
    private final List<AnalyzedSql> queries;
    private final TableStructure structure;
    private final String schema;


    private final List<TableReference> joinTables;
    private final List<TableReference> relatedTables;

    private Table(String name,
                  int numberOfQueries,
                  double totalQueryTime,
                  List<AnalyzedSql> queries,
                  TableStructure structure,
                  String schema,
                  List<TableReference> joinTables,
                  List<TableReference> relatedTables) {
        this.name = name;
        this.numberOfQueries = numberOfQueries;
        this.totalQueryTime = totalQueryTime;
        this.queries = queries;
        this.structure = structure;
        this.schema = schema;
        this.joinTables = joinTables;
        this.relatedTables = relatedTables;
    }

    public String getName() {
        return name;
    }

    public int getNumberOfQueries() {
        return numberOfQueries;
    }

    public double getTotalQueryTime() {
        return totalQueryTime;
    }

    public List<AnalyzedSql> getQueries() {
        return queries;
    }

    public String getSchema() {
        return schema;
    }

    public TableStructure getStructure() {
        return structure;
    }

    public List<TableReference> getJoinTables() {
        return joinTables;
    }

    public List<TableReference> getRelatedTables() {
        return relatedTables;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private int numberOfQueries;
        private double totalQueryTime;
        private List<AnalyzedSql> queries;
        private TableStructure structure;
        private String schema;
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

        public Builder queries(List<AnalyzedSql> queries) {
            this.queries = queries;
            return this;
        }

        public Builder structure(TableStructure structure) {
            this.structure = structure;
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
            return new Table(name, numberOfQueries, totalQueryTime, queries, structure, schema, joinTables, relatedTables);
        }

    }
}
