package com.github.safetytrick.sqlexplorer.environment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the known {@link Environment}s. Built from {@code environments.*}
 * config, or — when none is configured — a single "default" environment
 * synthesized from the legacy {@code datasources.metadata.*} keys.
 */
public class EnvironmentRegistry {

    public static final String DEFAULT_ENVIRONMENT = "default";

    private static final Logger log = LoggerFactory.getLogger(EnvironmentRegistry.class);

    private final Map<String, Environment> environments = new LinkedHashMap<>();
    private final String defaultEnvironmentName;

    public EnvironmentRegistry(Map<String, EnvironmentDefinition> definitions,
                               MetadataDataSourceProperties legacyMetadata,
                               SchemaRepositoryFactory schemaRepositoryFactory) {
        if (definitions != null && !definitions.isEmpty()) {
            for (Map.Entry<String, EnvironmentDefinition> entry : definitions.entrySet()) {
                environments.put(entry.getKey(), toEnvironment(entry.getKey(), entry.getValue(), schemaRepositoryFactory));
            }
            this.defaultEnvironmentName = resolveDefaultName(definitions);
        } else {
            ConnectionInfo connectionInfo = new ConnectionInfo(legacyMetadata.getJdbcUrl(),
                legacyMetadata.getUsername(),
                legacyMetadata.getPassword());
            // legacy fallback provides a connection (for schema) but no sources
            DatabaseServer server = new DatabaseServer(DEFAULT_ENVIRONMENT, connectionInfo, List.of(), schemaRepositoryFactory);
            environments.put(DEFAULT_ENVIRONMENT, new Environment(DEFAULT_ENVIRONMENT, List.of(server)));
            this.defaultEnvironmentName = DEFAULT_ENVIRONMENT;
        }
        log.info("Environments configured: {} (default: {})", environments.keySet(), defaultEnvironmentName);
    }

    /**
     * The environment marked {@code default: true} wins; else one literally named
     * "default"; else the first declared (single-file configs only — profile
     * overlays do not preserve declaration order).
     */
    private String resolveDefaultName(Map<String, EnvironmentDefinition> definitions) {
        List<String> marked = definitions.entrySet().stream()
            .filter(entry -> entry.getValue().isDefault())
            .map(Map.Entry::getKey)
            .toList();
        if (marked.size() > 1) {
            throw new IllegalArgumentException("Multiple environments marked default: " + marked);
        }
        if (!marked.isEmpty()) {
            return marked.getFirst();
        }
        return environments.containsKey(DEFAULT_ENVIRONMENT)
            ? DEFAULT_ENVIRONMENT
            : environments.keySet().iterator().next();
    }

    private static Environment toEnvironment(String name, EnvironmentDefinition definition,
                                             SchemaRepositoryFactory schemaRepositoryFactory) {
        List<DatabaseServer> servers = new ArrayList<>();
        for (Map.Entry<String, ServerDefinition> serverEntry : definition.getServers().entrySet()) {
            ServerDefinition server = serverEntry.getValue();
            ConnectionInfo connectionInfo = new ConnectionInfo(server.getJdbcUrl(), server.getUsername(), server.getPassword());
            servers.add(new DatabaseServer(serverEntry.getKey(), connectionInfo, server.getSources(), schemaRepositoryFactory));
        }
        return new Environment(name, servers, definition.isExplainEnabled());
    }

    public Environment getDefault() {
        return environments.get(defaultEnvironmentName);
    }

    public String getDefaultName() {
        return defaultEnvironmentName;
    }

    /** @return the named environment, or the default if {@code name} is null/unknown. */
    public Environment getOrDefault(String name) {
        Environment environment = name == null ? null : environments.get(name);
        return environment != null ? environment : getDefault();
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
