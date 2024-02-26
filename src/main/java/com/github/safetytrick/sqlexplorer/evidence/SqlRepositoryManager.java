package com.github.safetytrick.sqlexplorer.evidence;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SqlRepositoryManager {

    private static final Logger log = LoggerFactory.getLogger(SqlRepositoryManager.class);

    private final SqlEvidenceInputService sqlEvidenceInputService;
    private final List<SqlRepository> repositories = new CopyOnWriteArrayList<>();
    private CompositeSqlRepository compositeSqlRepository = new CompositeSqlRepository(Collections.emptyList());

    public SqlRepositoryManager(SqlEvidenceInputService sqlEvidenceInputService) {
        this.sqlEvidenceInputService = sqlEvidenceInputService;
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

        List<SqlRepository> repositories = sources.parallel().map(source -> {
            try {
                log.info("Loading source: {}", source);

                return sqlEvidenceInputService.fromPerformanceSchema(source, source.getSql());
            } catch (IOException e) {
                log.warn("Unable to process performance schema", e);
                return null;
            }
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
