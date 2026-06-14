package com.github.safetytrick.sqlexplorer.environment;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

/**
 * Holds the known {@link Environment}s. Phase 1: exactly one implicit
 * "default" environment with a single server assembled from the legacy
 * {@code datasources.metadata.*} configuration.
 */
@Service
public class EnvironmentRegistry {

    public static final String DEFAULT_ENVIRONMENT = "default";

    private final Map<String, Environment> environments;

    public EnvironmentRegistry(MetadataDataSourceProperties metadataProperties,
                               SchemaRepositoryFactory schemaRepositoryFactory) {
        ConnectionInfo connectionInfo = new ConnectionInfo(metadataProperties.getJdbcUrl(),
            metadataProperties.getUsername(),
            metadataProperties.getPassword());
        DatabaseServer server = new DatabaseServer(DEFAULT_ENVIRONMENT, connectionInfo, schemaRepositoryFactory);
        this.environments = Map.of(DEFAULT_ENVIRONMENT, new Environment(DEFAULT_ENVIRONMENT, List.of(server)));
    }

    public Environment getDefault() {
        return environments.get(DEFAULT_ENVIRONMENT);
    }

    public Environment get(String name) {
        return environments.get(name);
    }

    public Collection<Environment> list() {
        return environments.values();
    }

    @PreDestroy
    void close() {
        environments.values().forEach(Environment::close);
    }
}
