package com.github.safetytrick.sqlexplorer.evidence;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Holds the loaded SQL repositories per environment. Loading sources for one
 * environment replaces only that environment's repositories; queries resolve
 * the repository for the environment selected on the request.
 */
@Service
public class SqlRepositoryManager {

    private static final Logger log = LoggerFactory.getLogger(SqlRepositoryManager.class);

    private final SqlEvidenceInputService sqlEvidenceInputService;
    private final EnvironmentRegistry environmentRegistry;
    private final CaptureRehydrator captureRehydrator;
    private final Clock clock;
    private final Map<String, EnvironmentRepositories> byEnvironment = new ConcurrentHashMap<>();

    public SqlRepositoryManager(SqlEvidenceInputService sqlEvidenceInputService,
                                EnvironmentRegistry environmentRegistry,
                                CaptureRehydrator captureRehydrator,
                                Clock clock) {
        this.sqlEvidenceInputService = sqlEvidenceInputService;
        this.environmentRegistry = environmentRegistry;
        this.captureRehydrator = captureRehydrator;
        this.clock = clock;
    }

    public SqlRepository getSqlRepository(String environmentName) {
        return byEnvironment.getOrDefault(environmentName, EnvironmentRepositories.EMPTY).composite();
    }

    public List<SqlRepository> getRepositories(String environmentName) {
        return byEnvironment.getOrDefault(environmentName, EnvironmentRepositories.EMPTY).repositories();
    }

    /** Load state for the environment, or {@code null} when nothing has been loaded into it. */
    public LoadState getLoadState(String environmentName) {
        EnvironmentRepositories repositories = byEnvironment.get(environmentName);
        if (repositories == null) {
            return null;
        }
        return new LoadState(repositories.repositories().size(),
            repositories.loadedOn(),
            repositories.composite().getImportStats());
    }

    /** Load into the default environment (used by the startup loaders). */
    public SqlImportStats loadFromSqlSources(Stream<? extends SqlSource> sources) {
        return loadFromSqlSources(environmentRegistry.getDefaultName(), sources);
    }

    public SqlImportStats loadFromSqlSources(String environmentName, Stream<? extends SqlSource> sources) {
        SqlSchemaRepository schema = environmentRegistry.get(environmentName).schemaRepository();

        List<SqlRepository> repositories = sources.parallel().map(source -> {
            log.info("Loading source into environment {}: {}", environmentName, source);

            return sqlEvidenceInputService.fromPerformanceSchema(source, source.getSql(), schema);
        }).toList();

        byEnvironment.put(environmentName, new EnvironmentRepositories(List.copyOf(repositories),
            new CompositeSqlRepository(repositories), clock.instant()));

        SqlImportStats stats = getSqlRepository(environmentName).getImportStats();
        log.info("Import stats for environment {}: {}", environmentName, stats);
        return stats;
    }

    /**
     * Rebuild an environment from its sources' latest captures rather than
     * importing them again.
     *
     * <p>For coming back to an environment that was dropped from heap: the
     * captures were written by this process from a live read, so they are what
     * heap held, and reading them costs a fraction of a re-import. Startup does
     * NOT use this — Performance Schema accumulates, so a live read is always
     * at least as complete as an earlier capture (sqlx-4ww).
     *
     * <p>All or nothing. A source whose capture is missing, unreadable, or
     * written by a different normalizer cannot be rebuilt, and a partially
     * rehydrated environment would be a corpus missing one server with nothing
     * saying so. The caller's answer to false is to load the environment
     * properly.
     *
     * @return whether every source was rebuilt
     */
    public boolean rehydrate(String environmentName, List<String> sourceRefs) {
        if (sourceRefs == null || sourceRefs.isEmpty()) {
            return false;
        }
        List<SqlRepository> repositories = new java.util.ArrayList<>();
        for (String sourceRef : sourceRefs) {
            SqlRepository repository = captureRehydrator.rehydrate(sourceRef);
            if (repository == null) {
                log.info("Cannot rehydrate environment {}: source {} has no usable capture",
                    environmentName, sourceRef);
                return false;
            }
            repositories.add(repository);
        }
        byEnvironment.put(environmentName, new EnvironmentRepositories(List.copyOf(repositories),
            new CompositeSqlRepository(repositories), clock.instant()));
        log.info("Rehydrated environment {} from {} capture(s)", environmentName, repositories.size());
        return true;
    }

    /** The source refs currently loaded into an environment, for rehydrating it later. */
    public List<String> sourceRefs(String environmentName) {
        return getRepositories(environmentName).stream()
            .map(SqlRepository::getSource)
            .filter(java.util.Objects::nonNull)
            .map(SqlSource::getSourceRef)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
    }

    public record LoadState(int sourceCount, Instant loadedOn, SqlImportStats importStats) { }

    private record EnvironmentRepositories(List<SqlRepository> repositories, CompositeSqlRepository composite,
                                           Instant loadedOn) {
        static final EnvironmentRepositories EMPTY =
            new EnvironmentRepositories(List.of(), new CompositeSqlRepository(List.of()), null);
    }
}
