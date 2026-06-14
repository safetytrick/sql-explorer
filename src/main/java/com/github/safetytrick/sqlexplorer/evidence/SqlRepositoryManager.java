package com.github.safetytrick.sqlexplorer.evidence;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SqlRepositoryManager {

    private static final Logger log = LoggerFactory.getLogger(SqlRepositoryManager.class);

    private final SqlEvidenceInputService sqlEvidenceInputService;
    private final EnvironmentRegistry environmentRegistry;
    private final List<SqlRepository> repositories = new CopyOnWriteArrayList<>();
    private CompositeSqlRepository compositeSqlRepository = new CompositeSqlRepository(Collections.emptyList());

    public SqlRepositoryManager(SqlEvidenceInputService sqlEvidenceInputService,
                                EnvironmentRegistry environmentRegistry) {
        this.sqlEvidenceInputService = sqlEvidenceInputService;
        this.environmentRegistry = environmentRegistry;
    }

    public synchronized void addSqlRepository(SqlRepository sqlRepository) {
        repositories.add(sqlRepository);
        compositeSqlRepository = new CompositeSqlRepository(repositories);
    }

    public SqlRepository getSqlRepository() {
        return compositeSqlRepository;
    }

    public List<SqlRepository> getRepositories() {
        return Collections.unmodifiableList(repositories);
    }


    public SqlImportStats loadFromSqlSources(Stream<? extends SqlSource> sources) {

        // Phase 2: all sources load against the default environment's schema.
        // Phase 3 will attribute sources to their owning server and pass that
        // server's schema here (see docs/DATA_ENVIRONMENTS.md).
        SqlSchemaRepository schema = environmentRegistry.getDefault().schemaRepository();

        List<SqlRepository> repositories = sources.parallel().map(source -> {
            log.info("Loading source: {}", source);

            return sqlEvidenceInputService.fromPerformanceSchema(source, source.getSql(), schema);
        }).toList();

        synchronized (this) {
            this.repositories.clear();
            this.repositories.addAll(repositories);
            compositeSqlRepository = new CompositeSqlRepository(this.repositories);
        }

        log.info("Overall import stats: {}", getSqlRepository().getImportStats());
        return getSqlRepository().getImportStats();
    }

}
