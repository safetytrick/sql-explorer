package com.github.safetytrick.sqlexplorer.environment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.safetytrick.sqlexplorer.commentary.Names;
import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import com.github.safetytrick.sqlexplorer.schema.SchemaSnapshot;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            DatabaseServer server = new DatabaseServer(DEFAULT_ENVIRONMENT, connectionInfo, List.of(), schemaRepositoryFactory);
            environments.put(DEFAULT_ENVIRONMENT, new Environment(DEFAULT_ENVIRONMENT, List.of(server)));
            this.defaultEnvironmentName = DEFAULT_ENVIRONMENT;
        }
        log.info("Environments configured: {} (default: {})", environments.keySet(), defaultEnvironmentName);
    }

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
        String nameProblem = Names.environmentNameProblem(name);
        if (nameProblem != null) {
            throw new IllegalArgumentException(nameProblem);
        }
        List<Cluster> clusters = new ArrayList<>();
        Set<String> serverNames = new HashSet<>();
        for (Map.Entry<String, ServerDefinition> serverEntry : definition.getServers().entrySet()) {
            claim(name, serverNames, serverEntry.getKey());
            clusters.add(Cluster.of(server(serverEntry.getKey(), serverEntry.getValue(), schemaRepositoryFactory,
                new SchemaSnapshot.Key(name, serverEntry.getKey()))));
        }
        for (Map.Entry<String, ClusterDefinition> clusterEntry : definition.getClusters().entrySet()) {
            String clusterName = clusterEntry.getKey();
            ClusterDefinition cluster = clusterEntry.getValue();
            Map<String, DatabaseServer> servers = new LinkedHashMap<>();
            for (Map.Entry<String, ServerDefinition> serverEntry : cluster.getServers().entrySet()) {
                claim(name, serverNames, serverEntry.getKey());
                servers.put(serverEntry.getKey(), server(serverEntry.getKey(), serverEntry.getValue(),
                    schemaRepositoryFactory, new SchemaSnapshot.Key(name, clusterName)));
            }
            List<DatabaseServer> schemaFrom = new ArrayList<>();
            for (String serverName : cluster.getSchemaFrom()) {
                DatabaseServer server = servers.get(serverName);
                if (server == null) {
                    throw new IllegalArgumentException("Environment " + name + ", cluster " + clusterName
                        + ": schemaFrom names " + serverName + ", which is not one of its servers "
                        + servers.keySet());
                }
                schemaFrom.add(server);
            }
            clusters.add(new Cluster(clusterName, List.copyOf(servers.values()), schemaFrom));
        }
        return Environment.ofClusters(name, clusters, definition.isExplainEnabled(), definition.getPullCron());
    }

    private static void claim(String environment, Set<String> serverNames, String serverName) {
        if (!serverNames.add(serverName)) {
            throw new IllegalArgumentException("Environment " + environment + " declares server "
                + serverName + " more than once; server names must be unique across its clusters");
        }
    }

    private static DatabaseServer server(String name, ServerDefinition definition,
                                         SchemaRepositoryFactory schemaRepositoryFactory,
                                         SchemaSnapshot.Key snapshotKey) {
        ConnectionInfo connectionInfo = new ConnectionInfo(definition.getJdbcUrl(), definition.getUsername(),
            definition.getPassword());
        Set<ServerRole> roles = definition.getRoles() == null ? Set.of() : definition.getRoles();
        if (roles.contains(ServerRole.PRIMARY) && roles.contains(ServerRole.SAMPLING)) {
            throw new IllegalArgumentException("Environment " + snapshotKey.environment() + ", server " + name
                + ": a primary cannot also have the sampling role; give it to a replica");
        }
        return new DatabaseServer(name, connectionInfo, definition.getSources(), schemaRepositoryFactory, snapshotKey,
            roles);
    }

    public Environment getDefault() {
        return environments.get(defaultEnvironmentName);
    }

    public String getDefaultName() {
        return defaultEnvironmentName;
    }

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
