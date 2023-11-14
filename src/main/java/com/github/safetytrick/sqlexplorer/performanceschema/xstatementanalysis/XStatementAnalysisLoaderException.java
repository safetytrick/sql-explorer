package com.github.safetytrick.sqlexplorer.performanceschema.xstatementanalysis;

import java.sql.SQLException;

public class XStatementAnalysisLoaderException extends RuntimeException {
    public XStatementAnalysisLoaderException(String message, SQLException e) {
        super(message, e);
    }
}
