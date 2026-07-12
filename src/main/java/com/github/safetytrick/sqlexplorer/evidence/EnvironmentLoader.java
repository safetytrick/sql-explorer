package com.github.safetytrick.sqlexplorer.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.environment.DatabaseServer;
import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import com.github.safetytrick.sqlexplorer.environment.SourceDefinition;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSourceFactory;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Builds and loads the SQL sources configured under each server
 * ({@code environments.<env>.servers.<server>.sources}) into an environment.
 * Each source definition is turned into {@link SqlSource}s by the
 * {@link SqlSourceFactory} matching its {@code type}, then all of an
 * environment's sources are loaded together.
 */
@Component
public class EnvironmentLoader {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentLoader.class);

    private final Map<String, SqlSourceFactory> factoriesByType;
    private final SqlRepositoryManager sqlRepositoryManager;
    private final EnvironmentRegistry environmentRegistry;

    public EnvironmentLoader(List<SqlSourceFactory> factories,
                             SqlRepositoryManager sqlRepositoryManager,
                             EnvironmentRegistry environmentRegistry) {
        this.factoriesByType = factories.stream()
            .collect(Collectors.toMap(SqlSourceFactory::type, Function.identity()));
        this.sqlRepositoryManager = sqlRepositoryManager;
        this.environmentRegistry = environmentRegistry;
    }

    @PostConstruct
    void loadConfiguredSourcesAtStartup() {
        loadConfiguredSources(environmentRegistry.getDefaultName());
    }

    /** Build every configured source for the named environment and load them together. */
    public SqlImportStats loadConfiguredSources(String environmentName) {
        return load(environmentName, Stream.empty());
    }

    /**
     * Load the environment's configured sources together with the given extra sources
     * (e.g. persisted uploads). Loading replaces the environment's repositories, so the
     * configured sources must be part of every load or they would be dropped.
     */
    public SqlImportStats load(String environmentName, Stream<? extends SqlSource> extraSources) {
        List<SqlSource> sources = buildConfiguredSources(environmentName);
        List<SqlSource> all = Stream.concat(sources.stream(), extraSources).toList();

        if (all.isEmpty()) {
            log.info("No sources for environment {}; nothing to load", environmentName);
            return SqlImportStats.builder().build();
        }
        log.info("Loading {} source(s) into environment {} ({} configured)",
            all.size(), environmentName, sources.size());
        return sqlRepositoryManager.loadFromSqlSources(environmentName, all.stream());
    }

    private List<SqlSource> buildConfiguredSources(String environmentName) {
        Environment environment = environmentRegistry.get(environmentName);
        List<SqlSource> sources = new ArrayList<>();
        for (DatabaseServer server : environment.getServers()) {
            for (SourceDefinition definition : server.getSourceDefinitions()) {
                SqlSourceFactory factory = factoriesByType.get(definition.getType());
                if (factory == null) {
                    throw new IllegalArgumentException("Unknown source type '" + definition.getType()
                        + "' on server " + server.getName());
                }
                factory.create(environmentName, server, definition).forEach(sources::add);
            }
        }
        return sources;
    }
}
