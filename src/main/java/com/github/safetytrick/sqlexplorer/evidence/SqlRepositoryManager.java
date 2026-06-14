package com.github.safetytrick.sqlexplorer.evidence;

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
    private final Map<String, EnvironmentRepositories> byEnvironment = new ConcurrentHashMap<>();

    public SqlRepositoryManager(SqlEvidenceInputService sqlEvidenceInputService,
                                EnvironmentRegistry environmentRegistry) {
        this.sqlEvidenceInputService = sqlEvidenceInputService;
        this.environmentRegistry = environmentRegistry;
    }

    public SqlRepository getSqlRepository(String environmentName) {
        return byEnvironment.getOrDefault(environmentName, EnvironmentRepositories.EMPTY).composite();
    }

    public List<SqlRepository> getRepositories(String environmentName) {
        return byEnvironment.getOrDefault(environmentName, EnvironmentRepositories.EMPTY).repositories();
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
            new CompositeSqlRepository(repositories)));

        SqlImportStats stats = getSqlRepository(environmentName).getImportStats();
        log.info("Import stats for environment {}: {}", environmentName, stats);
        return stats;
    }

    private record EnvironmentRepositories(List<SqlRepository> repositories, CompositeSqlRepository composite) {
        static final EnvironmentRepositories EMPTY =
            new EnvironmentRepositories(List.of(), new CompositeSqlRepository(List.of()));
    }
}
