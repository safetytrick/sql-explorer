package com.github.safetytrick.sqlexplorer.evidence.xstatementanalysis;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.evidence.source.MeteredSql;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlInput;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class XStatementAnalysisSqlSource implements SqlSource, AutoCloseable {

    private final String sourceRef;
    private final Clock clock;
    private Instant createdOn;
    private final Object lock = new Object();

    /**
     * Read alongside the rows and then frozen. Reading it later would describe
     * the server as it is now rather than as it was at capture, which is the
     * whole distinction (sqlx-sy5).
     */
    private volatile java.util.Map<String, String> serverContext = java.util.Map.of();

    private final SingleConnectionDataSource dataSource;

    public XStatementAnalysisSqlSource(String sourceRef,
                                       String jdbcUrl,
                                       String username,
                                       String password,
                                       Clock clock) {
        this.sourceRef = sourceRef;
        this.createdOn = clock.instant();
        this.dataSource = new SingleConnectionDataSource(jdbcUrl, username, password, false);
        this.clock = clock;
    }

    @Override
    public Stream<SqlInput> getSql() {
        createdOn = clock.instant();
        synchronized (lock) {
            try {
                XStatementAnalysisDao saDao = new XStatementAnalysisDao(dataSource.getConnection());
                // On the same connection, in the same breath as the rows: the
                // settings describe the capture, not the moment someone asks.
                serverContext = saDao.getCaptureContext();
                List<MeteredSql> statementAnalysis = saDao.getXStatementAnalysis();
                List<SqlInput> result = new ArrayList<>(statementAnalysis.size());
                for (int i = 0; i < statementAnalysis.size(); i++) {
                    result.add(new SqlInput(i, statementAnalysis.get(i), this));
                }
                return result.parallelStream();
            } catch (SQLException e) {
                throw new XStatementAnalysisLoaderException("Unable to load sql from: " + sourceRef, e);
            }
        }
    }

    @Override
    public Instant getCreatedOn() {
        return createdOn;
    }

    @Override
    public java.util.Map<String, String> getCaptureServerContext() {
        return serverContext;
    }

    @Override
    public String getSourceRef() {
        return sourceRef;
    }

    @Override
    public void close() {
        dataSource.destroy();
    }

    @Override
    public String toString() {
        return getSourceRef() + "@" + getCreatedOn();
    }
}
