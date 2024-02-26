package com.github.safetytrick.sqlexplorer.graph;

public record CrossSchemaTable(
    String query,
    String id,
    String analysisUrl,
    String grafanaUrl,
    String rundeckQuery,
    String tables
) {

}
