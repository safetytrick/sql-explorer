package com.github.safetytrick.sqlexplorer.environment;

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

    public Environment(String name, List<DatabaseServer> servers) {
        this.name = name;
        this.servers = List.copyOf(servers);
    }

    public String getName() {
        return name;
    }

    public List<DatabaseServer> getServers() {
        return servers;
    }

    public SqlSchemaRepository schemaRepository() {
        if (servers.size() == 1) {
            return servers.getFirst().getSchemaRepository();
        }
        return new CompositeSchemaRepository(servers.stream()
            .map(DatabaseServer::getSchemaRepository)
            .toList());
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
