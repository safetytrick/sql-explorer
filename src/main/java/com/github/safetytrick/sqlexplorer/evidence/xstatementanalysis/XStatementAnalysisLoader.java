package com.github.safetytrick.sqlexplorer.evidence.xstatementanalysis;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSourceProvider;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@ConditionalOnProperty(value="x-statement-analysis.enabled", havingValue = "true")
@Component
class XStatementAnalysisLoader implements SqlSourceProvider {
    private static final Logger log = LoggerFactory.getLogger(XStatementAnalysisLoader.class);

    private final XStatementAnalysisLoaderConfig config;
    private final Clock clock;
    @Inject
    public XStatementAnalysisLoader(XStatementAnalysisLoaderConfig config,
                                    Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    @Override
    public Stream<? extends SqlSource> sqlSources() {
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
        return result.stream();
    }
}
