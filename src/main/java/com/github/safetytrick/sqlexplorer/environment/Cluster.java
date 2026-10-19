package com.github.safetytrick.sqlexplorer.environment;

import java.util.ArrayList;
import java.util.List;

import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;

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

    /**
     * The cluster's schema, read from the first {@code schemaFrom} server that
     * can supply it.
     *
     * <p>Cheap to call repeatedly: each server keeps the schema it loaded, and
     * the failure it met, until a load or pull asks again — so this walks a
     * list of cached answers rather than reconnecting.
     *
     * @throws RuntimeException the first server's failure, when none can supply it
     */
    public SchemaSource readSchema() {
        List<String> failures = new ArrayList<>();
        RuntimeException first = null;
        for (DatabaseServer server : schemaFrom) {
            try {
                SqlSchemaRepository schema = server.getSchemaRepository();
                return new SchemaSource(schema, server.getName(), server != schemaFrom.getFirst(),
                    List.copyOf(failures));
            } catch (RuntimeException e) {
                failures.add(server.getName() + ": " + e.getMessage());
                if (first == null) {
                    first = e;
                }
            }
        }
        throw first;
    }

    /**
     * Where a cluster's schema came from.
     *
     * @param server   the {@code schemaFrom} server that supplied it
     * @param fallback whether that was not the first choice — the servers
     *                 before it are in {@code passedOver}, with why
     */
    public record SchemaSource(SqlSchemaRepository schema, String server, boolean fallback,
                               List<String> passedOver) { }
}
