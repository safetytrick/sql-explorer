package com.github.safetytrick.sqlexplorer.environment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
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
     * can supply it, or — when none can — its last snapshot, which is then
     * not current (sqlx-sqy.4).
     *
     * <p>Cheap to call repeatedly: each server keeps the schema it loaded, and
     * the failure it met, until a load or pull asks again — so this walks a
     * list of cached answers rather than reconnecting.
     *
     * @throws RuntimeException the first server's failure, when no server can
     *                          supply the schema and there is no snapshot either
     */
    public SchemaSource readSchema() {
        List<String> failures = new ArrayList<>();
        RuntimeException first = null;
        for (DatabaseServer server : schemaFrom) {
            try {
                SchemaRepositoryFactory.SchemaLoad load = server.getSchemaLoad();
                return new SchemaSource(load.schema(), server.getName(), server != schemaFrom.getFirst(),
                    List.copyOf(failures), load.current(), load.fromSnapshot(), load.readAt());
            } catch (RuntimeException e) {
                failures.add(server.getName() + ": " + e.getMessage());
                if (first == null) {
                    first = e;
                }
            }
        }
        Optional<SchemaRepositoryFactory.SchemaLoad> lastKnown = schemaFrom.getFirst().lastKnownSchema();
        if (lastKnown.isPresent()) {
            SchemaRepositoryFactory.SchemaLoad load = lastKnown.get();
            return new SchemaSource(load.schema(), load.readFrom(), true, List.copyOf(failures), false, true,
                load.readAt());
        }
        throw first;
    }

    /**
     * Where a cluster's schema came from.
     *
     * @param server       the server it was read from — for a snapshot, the one
     *                     that was read when the snapshot was taken
     * @param fallback     whether that was not the first choice — the servers
     *                     tried before it are in {@code passedOver}, with why
     * @param current      whether a server confirmed it matches the schema now.
     *                     False only for a snapshot no reachable server
     *                     vouched for: it serves what was already analysed, and
     *                     nothing new is analysed against it
     * @param fromSnapshot read from the cluster's snapshot rather than the server
     * @param readAt       when the schema was read from a server
     */
    public record SchemaSource(SqlSchemaRepository schema, String server, boolean fallback,
                               List<String> passedOver, boolean current, boolean fromSnapshot, Instant readAt) { }
}
