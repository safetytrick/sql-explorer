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
    public com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext.Origin getCaptureOrigin() {
        return com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext.Origin.PERFORMANCE_SCHEMA;
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
