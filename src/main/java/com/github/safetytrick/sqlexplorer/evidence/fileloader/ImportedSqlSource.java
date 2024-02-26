package com.github.safetytrick.sqlexplorer.evidence.fileloader;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.safetytrick.sqlexplorer.evidence.SqlImportStats;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;

public class ImportedSqlSource {
    private SqlSource source;
    private String status;
    private SqlImportStats importStats;

    public ImportedSqlSource(@JsonProperty SqlSource source,
                             @JsonProperty String status,
                             @JsonProperty SqlImportStats importStats) {
        this.source = source;
        this.status = status;
        this.importStats = importStats;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public SqlSource getSource() {
        return source;
    }

    public void setSource(SqlSource source) {
        this.source = source;
    }

    public SqlImportStats getImportStats() {
        return importStats;
    }

    public void setImportStats(SqlImportStats importStats) {
        this.importStats = importStats;
    }
}
