package com.github.safetytrick.sqlexplorer.environment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.github.safetytrick.sqlexplorer.schema.SchemaFingerprint;
import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;

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

    public static Cluster of(DatabaseServer server) {
        return new Cluster(server.getName(), List.of(server), List.of(server));
    }

    public SchemaSource readSchema() {
        List<String> failures = new ArrayList<>();
        RuntimeException first = null;
        for (DatabaseServer server : schemaFrom) {
            try {
                SchemaRepositoryFactory.SchemaLoad load = server.getSchemaLoad();
                return new SchemaSource(load.schema(), server.getName(), server != schemaFrom.getFirst(),
                    List.copyOf(failures), load.current(), load.fromSnapshot(), load.readAt(), load.fingerprint());
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
                load.readAt(), load.fingerprint());
        }
        throw first;
    }

    public record SchemaSource(SqlSchemaRepository schema, String server, boolean fallback,
                               List<String> passedOver, boolean current, boolean fromSnapshot, Instant readAt,
                               SchemaFingerprint fingerprint) { }
}
