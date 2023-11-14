package com.github.safetytrick.sqlexplorer.performanceschema.xstatementanalysis;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.service.SqlRepositoryManager;
import com.github.safetytrick.sqlexplorer.source.SqlSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@ConditionalOnProperty(value="x-statement-analysis.enabled", havingValue = "true")
@Component
public class XStatementAnalysisLoader {
    private static final Logger log = LoggerFactory.getLogger(XStatementAnalysisLoader.class);

    private final SqlRepositoryManager sqlRepositoryManager;
    private final XStatementAnalysisLoaderConfig config;
    private final Clock clock;
    @Inject
    public XStatementAnalysisLoader(SqlRepositoryManager sqlRepositoryManager,
                                    XStatementAnalysisLoaderConfig config,
                                    Clock clock) {
        this.sqlRepositoryManager = sqlRepositoryManager;
        this.config = config;
        this.clock = clock;
    }

    @PostConstruct
    public void initialize() {
        if (!config.isEnabled()) {
            throw new IllegalArgumentException("XStatementAnalysisLoader is not enabled");
        }

        sqlRepositoryManager.loadFromSqlSources(getSqlSources().parallelStream());
    }

    private List<SqlSource> getSqlSources() {
        List<SqlSource> result = new ArrayList<>();
        for (Map.Entry<String, MySqlStatementAnalysisHost> hostEntry : config.getHosts().entrySet()) {
            MySqlStatementAnalysisHost host = hostEntry.getValue();
            log.info("Loading x$statement_analysis from: {} as {}", host.getJdbcUrl(), hostEntry.getKey());

            var sqlSource = new XStatementAnalysisSqlSource(hostEntry.getKey(),
                host.getJdbcUrl(),
                host.getUsername(),
                host.getPassword(),
                clock);

            result.add(sqlSource);
        }
        return result;
    }
}
