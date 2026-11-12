package com.github.safetytrick.sqlexplorer.auth;

/**
 * Something a request may do, at one level of scope (sqlx-e13.4). Roles are
 * fixed bundles of these ({@link Role}); a grant puts a role on a scope
 * ({@link Grant}). Written in config and the API as {@code explorer:read}.
 */
public enum Permission {

    // ── environment ─────────────────────────────────────────────────────
    /** Read an environment's analysis, schema and captures. */
    EXPLORER_READ("explorer:read", Level.ENVIRONMENT),
    /** Tag, note and annotate in an environment. Everyone with access has it. */
    COMMENTARY_WRITE("commentary:write", Level.ENVIRONMENT),
    /** Run EXPLAIN against an environment's servers. */
    ENV_EXPLAIN("env:explain", Level.ENVIRONMENT),
    /** Sample table data on an environment's servers (profiling). */
    ENV_SAMPLE("env:sample", Level.ENVIRONMENT),
    /** Pull or reload an environment, which takes it live. */
    ENV_PULL("env:pull", Level.ENVIRONMENT),

    // ── datasource (managed queries, sqlx-tev) ─────────────────────────
    QUERY_READ("query:read", Level.DATASOURCE),
    QUERY_RUN("query:run", Level.DATASOURCE),
    QUERY_AUTHOR("query:author", Level.DATASOURCE),

    // ── global ──────────────────────────────────────────────────────────
    /** Operator pages and anything not about one environment. */
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
