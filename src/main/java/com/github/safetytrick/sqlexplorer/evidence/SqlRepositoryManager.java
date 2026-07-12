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
    private final Clock clock;
    private final Map<String, EnvironmentRepositories> byEnvironment = new ConcurrentHashMap<>();

    public SqlRepositoryManager(SqlEvidenceInputService sqlEvidenceInputService,
                                EnvironmentRegistry environmentRegistry,
                                Clock clock) {
        this.sqlEvidenceInputService = sqlEvidenceInputService;
        this.environmentRegistry = environmentRegistry;
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

    public record LoadState(int sourceCount, Instant loadedOn, SqlImportStats importStats) { }

    private record EnvironmentRepositories(List<SqlRepository> repositories, CompositeSqlRepository composite,
                                           Instant loadedOn) {
        static final EnvironmentRepositories EMPTY =
            new EnvironmentRepositories(List.of(), new CompositeSqlRepository(List.of()), null);
    }
}
