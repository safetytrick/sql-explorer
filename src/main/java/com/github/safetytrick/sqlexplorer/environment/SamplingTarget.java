package com.github.safetytrick.sqlexplorer.environment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import com.zaxxer.hikari.HikariDataSource;

public record SamplingTarget(String environment, DatabaseServer server, SamplingLimits limits) {

    public SamplingSession openSession() throws SQLException {
        DataSource dataSource = server.getDataSource();
        Connection connection = dataSource.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION TRANSACTION READ ONLY");
            statement.execute("SET SESSION max_execution_time = " + limits.maxExecutionTime().toMillis());
        } catch (SQLException | RuntimeException e) {
            discard(dataSource, connection);
            throw e;
        }
        return new SamplingSession(dataSource, connection, limits);
    }

    private static void discard(DataSource dataSource, Connection connection) {
        if (dataSource instanceof HikariDataSource hikari) {
            hikari.evictConnection(connection);
        } else {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    public static final class SamplingSession implements AutoCloseable {

        private final DataSource dataSource;
        private final Connection connection;
        private final SamplingLimits limits;

        private SamplingSession(DataSource dataSource, Connection connection, SamplingLimits limits) {
            this.dataSource = dataSource;
            this.connection = connection;
            this.limits = limits;
        }

        public Connection connection() {
            return connection;
        }

        public SamplingLimits limits() {
            return limits;
        }

        @Override
        public void close() {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET SESSION max_execution_time = DEFAULT");
                statement.execute("SET SESSION TRANSACTION READ WRITE");
            } catch (SQLException | RuntimeException e) {
                discard(dataSource, connection);
                return;
            }
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }
}
