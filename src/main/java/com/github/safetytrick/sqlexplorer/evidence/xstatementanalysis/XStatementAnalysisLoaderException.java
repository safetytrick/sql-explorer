package com.github.safetytrick.sqlexplorer.evidence.xstatementanalysis;

import java.sql.SQLException;

class XStatementAnalysisLoaderException extends RuntimeException {
    public XStatementAnalysisLoaderException(String message, SQLException e) {
        super(message, e);
    }
}
