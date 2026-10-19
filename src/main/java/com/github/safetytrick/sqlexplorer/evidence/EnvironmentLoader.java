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
import com.github.safetytrick.sqlexplorer.analysis.AnalysisFailureRecorder;
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
    private final AnalysisFailureRecorder analysisFailureRecorder;

    public EnvironmentLoader(List<SqlSourceFactory> factories,
                             SqlRepositoryManager sqlRepositoryManager,
                             EnvironmentRegistry environmentRegistry,
                             AnalysisFailureRecorder analysisFailureRecorder) {
        this.factoriesByType = factories.stream()
            .collect(Collectors.toMap(SqlSourceFactory::type, Function.identity()));
        this.sqlRepositoryManager = sqlRepositoryManager;
        this.environmentRegistry = environmentRegistry;
        this.analysisFailureRecorder = analysisFailureRecorder;
    }

    /**
     * Load every configured environment, default first.
     *
     * <p>WHY ALL OF THEM. Only the default used to load, so a non-default
     * environment read as an EMPTY CORPUS after every restart — not as
     * unloaded. Its pages rendered "0 analyzed" and its unqueried report
     * claimed every table was untouched, which is the shape of wrong answer
     * this codebase spends most of its effort avoiding. Nothing chose that; it
     * fell out of which environment happened to be default (sqlx-v91).
     *
     * <p>Loading also CAPTURES: each source is read live and its capture
     * written, so loading every environment makes a restart a sampling point
     * for all of them rather than for one. That matters because Performance
     * Schema is cumulative and cleared on reset — an environment nobody loads
     * is an environment whose history is never recorded.
     *
     * <p>DEFAULT FIRST, because it is the one someone is most likely to open,
     * and because the resident cap ({@code sql-explorer.resident-environments},
     * 3) evicts least-recently-used environments once it is exceeded — so the
     * order here decides what is still in heap when startup finishes. An
     * evicted environment is not lost: it rehydrates from the capture this load
     * just wrote.
     *
     * <p>NO ENVIRONMENT'S FAILURE STOPS STARTUP, and that is a change. A load
     * throws when its server is unreachable, and this used to run for one
     * environment inside {@code @PostConstruct}, so that single failure took the
     * whole application down. With every environment loading, the chance that
     * one is unreachable is only higher — and an application that boots and
     * reports which environment it could not read is strictly more useful than
     * one that does not boot at all.
     */
    @PostConstruct
    void loadConfiguredSourcesAtStartup() {
        List<String> names = new ArrayList<>();
        names.add(environmentRegistry.getDefaultName());
        environmentRegistry.list().stream()
            .map(Environment::getName)
            .filter(name -> !names.contains(name))
            .forEach(names::add);

        for (String name : names) {
            try {
                loadConfiguredSources(name);
            } catch (RuntimeException e) {
                log.warn("Could not load environment {} at startup; it will be empty until "
                    + "something pulls it: {}", name, e.toString());
            }
        }
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
        // Analysis is about to run again over the whole source, so anything
        // captured from the previous run describes a corpus that no longer
        // exists. Dropping it here keeps the captures and the profiles
        // describing the same import.
        analysisFailureRecorder.clear();
        // A load is a fresh attempt at every server, including one whose schema
        // could not be read last time: it may have been fixed since (sqlx-jdf).
        // A schema already in memory is kept only if the server's fingerprint
        // still matches it — a cheap check, where reloading was not (sqlx-sqy.4).
        Environment environment = environmentRegistry.get(environmentName);
        if (environment != null) {
            environment.revalidateSchemas();
        }
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
