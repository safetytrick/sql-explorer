package com.github.safetytrick.sqlexplorer.auth;

public enum Permission {

    EXPLORER_READ("explorer:read", Level.ENVIRONMENT),
    COMMENTARY_WRITE("commentary:write", Level.ENVIRONMENT),
    ENV_EXPLAIN("env:explain", Level.ENVIRONMENT),
    ENV_SAMPLE("env:sample", Level.ENVIRONMENT),
    ENV_PULL("env:pull", Level.ENVIRONMENT),

    QUERY_READ("query:read", Level.DATASOURCE),
    QUERY_RUN("query:run", Level.DATASOURCE),
    QUERY_AUTHOR("query:author", Level.DATASOURCE),

    ADMIN("admin", Level.GLOBAL);

    public enum Level { GLOBAL, ENVIRONMENT, DATASOURCE }

    private final String key;
    private final Level level;

    Permission(String key, Level level) {
        this.key = key;
        this.level = level;
    }

    public String key() {
        return key;
    }

    public Level level() {
        return level;
    }
}
