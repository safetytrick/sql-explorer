package com.github.safetytrick.sqlexplorer.evidence;

import java.util.List;
import java.util.stream.Collectors;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSourceProvider;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Loads the configured {@link SqlSourceProvider}s (x$statement_analysis,
 * performance-schema files, ...) into an environment. Replaces the previous
 * {@code @PostConstruct} loaders that each self-triggered and clobbered the
 * single global repository; all providers now load together into one
 * environment.
 */
@Component
public class EnvironmentLoader {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentLoader.class);

    private final List<SqlSourceProvider> providers;
    private final SqlRepositoryManager sqlRepositoryManager;
    private final EnvironmentRegistry environmentRegistry;

    public EnvironmentLoader(List<SqlSourceProvider> providers,
                             SqlRepositoryManager sqlRepositoryManager,
                             EnvironmentRegistry environmentRegistry) {
        this.providers = providers;
        this.sqlRepositoryManager = sqlRepositoryManager;
        this.environmentRegistry = environmentRegistry;
    }

    @PostConstruct
    void loadConfiguredSourcesAtStartup() {
        if (providers.isEmpty()) {
            log.info("No configured SQL source providers; nothing to load at startup");
            return;
        }
        loadConfiguredSources(environmentRegistry.getDefaultName());
    }

    /** Gather every provider's sources and load them into the named environment. */
    public SqlImportStats loadConfiguredSources(String environmentName) {
        List<SqlSource> sources = providers.stream()
            .flatMap(SqlSourceProvider::sqlSources)
            .collect(Collectors.toList());
        log.info("Loading {} configured source(s) into environment {}", sources.size(), environmentName);
        return sqlRepositoryManager.loadFromSqlSources(environmentName, sources.stream());
    }
}
