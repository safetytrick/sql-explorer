package com.github.safetytrick.sqlexplorer.analysis;

public class UnsupportedSqlException extends RuntimeException {
    public UnsupportedSqlException() {
        super();
    }

    public UnsupportedSqlException(String message) {
        super(message);
    }
}
