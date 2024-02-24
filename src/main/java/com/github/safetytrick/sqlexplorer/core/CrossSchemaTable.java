package com.github.safetytrick.sqlexplorer.core;

public record CrossSchemaTable(
    String query,
    String id,
    String analysisUrl,
    String grafanaUrl,
    String rundeckQuery,
    String tables
) {

}
