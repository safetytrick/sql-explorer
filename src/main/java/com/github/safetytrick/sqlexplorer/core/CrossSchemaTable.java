package com.github.safetytrick.sqlexplorer.core;

public class CrossSchemaTable {
    final String query;
    final String id;
    final String analysisUrl;
    final String grafanaUrl;
    final String rundeckQuery;
    final String tables;

    public String getQuery() {
        return query;
    }

    public String getId(){
        return id;
    }

    public String getGrafanaUrl() {
        return grafanaUrl;
    }

    public String getAnalysisUrl() {
        return analysisUrl;
    }

    public String getRundeckQuery() {
        return rundeckQuery;
    }

    public String getTables() {
        return tables;
    }

    public CrossSchemaTable(String query, String id, String analysisUrl, String grafanaUrl, String rundeckQuery, String tables) {
        this.query = query;
        this.id = id;
        this.analysisUrl = analysisUrl;
        this.grafanaUrl = grafanaUrl;
        this.rundeckQuery = rundeckQuery;
        this.tables = tables;
    }
}
