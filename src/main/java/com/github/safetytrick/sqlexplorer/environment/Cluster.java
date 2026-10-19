package com.github.safetytrick.sqlexplorer.environment;

import java.util.List;

/**
 * A primary and its replicas within an {@link Environment}: servers that carry
 * one schema. All of them are captured from; {@link #schemaFrom()} lists, in
 * order of preference, the ones the schema may be read from.
 *
 * <p>An environment configured with a flat {@code servers:} block has one
 * cluster per server, named after it, reading schema from itself.
 */
public record Cluster(String name, List<DatabaseServer> servers, List<DatabaseServer> schemaFrom) {

    public Cluster {
        servers = List.copyOf(servers);
        schemaFrom = List.copyOf(schemaFrom);
        if (schemaFrom.isEmpty()) {
            throw new IllegalArgumentException("cluster " + name + " names no schemaFrom server");
        }
        for (DatabaseServer server : schemaFrom) {
            if (!servers.contains(server)) {
                throw new IllegalArgumentException("cluster " + name + " reads schema from "
                    + server.getName() + ", which is not one of its servers");
            }
        }
    }

    /** A cluster of one: the server is its own schema source. */
    public static Cluster of(DatabaseServer server) {
        return new Cluster(server.getName(), List.of(server), List.of(server));
    }
}
