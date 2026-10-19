package com.github.safetytrick.sqlexplorer.environment;

import java.util.ArrayList;
import java.util.List;

import com.github.safetytrick.sqlexplorer.schema.CompositeSchemaRepository;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;

/**
 * A named deployment context (staging, prod, ...) containing the database
 * servers whose SQL and schema are analyzed together.
 */
public final class Environment implements AutoCloseable {

    private final String name;
    private final List<DatabaseServer> servers;

    private final boolean explainEnabled;
    private final String pullCron;

    public Environment(String name, List<DatabaseServer> servers) {
        this(name, servers, false, null);
    }

    public Environment(String name, List<DatabaseServer> servers, boolean explainEnabled) {
        this(name, servers, explainEnabled, null);
    }

    public Environment(String name, List<DatabaseServer> servers, boolean explainEnabled, String pullCron) {
        this.explainEnabled = explainEnabled;
        this.pullCron = pullCron;
        this.name = name;
        this.servers = List.copyOf(servers);
    }

    /** Whether EXPLAIN may be run against this environment's servers. Off by default. */
    public boolean isExplainEnabled() {
        return explainEnabled;
    }

    /** Cron expression to pull this environment's sources on, or null for none. */
    public String getPullCron() {
        return pullCron;
    }

    public String getName() {
        return name;
    }

    public List<DatabaseServer> getServers() {
        return servers;
    }

    /**
     * The schema of every server that can be read.
     *
     * <p>A server whose schema cannot be read is left out rather than failing
     * the environment: one unreachable member of a seventeen-server
     * environment — a replica missing the reading user, an instance mid-rebuild
     * — used to take the other sixteen down with it, at startup and on every
     * pull (sqlx-jdf). Its absence is reported through its source, which
     * fails the same way and is listed on {@code /coverage}. Only when no
     * server can be read is there nothing to return, and then the first
     * failure is the answer.
     */
    public SqlSchemaRepository schemaRepository() {
        List<SqlSchemaRepository> readable = new ArrayList<>(servers.size());
        RuntimeException firstFailure = null;
        for (DatabaseServer server : servers) {
            try {
                readable.add(server.getSchemaRepository());
            } catch (RuntimeException e) {
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }
        if (readable.isEmpty() && firstFailure != null) {
            throw firstFailure;
        }
        return readable.size() == 1 ? readable.getFirst() : new CompositeSchemaRepository(readable);
    }

    /** Let every server whose schema failed to load be tried again on next use. */
    public void forgetSchemaFailures() {
        servers.forEach(DatabaseServer::forgetSchemaFailure);
    }

    /** Drop each server's cached schema so the next access reloads it. */
    public void refreshSchema() {
        servers.forEach(DatabaseServer::refreshSchema);
    }

    @Override
    public void close() {
        servers.forEach(DatabaseServer::close);
    }
}
