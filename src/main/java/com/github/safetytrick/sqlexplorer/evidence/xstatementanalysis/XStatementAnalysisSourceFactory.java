package com.github.safetytrick.sqlexplorer.evidence.xstatementanalysis;

import java.time.Clock;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.environment.ConnectionInfo;
import com.github.safetytrick.sqlexplorer.environment.DatabaseServer;
import com.github.safetytrick.sqlexplorer.environment.SourceDefinition;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSourceFactory;
import org.springframework.stereotype.Component;

/**
 * Reads x$statement_analysis over a server's own connection. Configured as
 * {@code - type: x-statement-analysis} under a server's sources.
 */
@Component
class XStatementAnalysisSourceFactory implements SqlSourceFactory {

    static final String TYPE = "x-statement-analysis";

    private final Clock clock;

    XStatementAnalysisSourceFactory(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Stream<? extends SqlSource> create(DatabaseServer server, SourceDefinition definition) {
        ConnectionInfo connection = server.getConnectionInfo();
        return Stream.of(new XStatementAnalysisSqlSource(server.getName(),
            connection.jdbcUrl(),
            connection.username(),
            connection.password(),
            clock));
    }
}
