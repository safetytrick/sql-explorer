package com.github.safetytrick.sqlexplorer.environment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import com.zaxxer.hikari.HikariDataSource;

/**
 * The one server of an environment SQL Explorer may run sampling queries on,
 * with the limits those queries must keep (sqlx-n5j.4). Obtained only from
 * {@link Environment#samplingTarget()}, which is the guard.
 *
 * @param environment the environment it belongs to
 * @param server      a server with the {@link ServerRole#SAMPLING} role
 * @param limits      what every query must observe; see {@link SamplingLimits}
 */
public record SamplingTarget(String environment, DatabaseServer server, SamplingLimits limits) {

    /**
     * A connection to the sampling server with the server-enforceable limits
     * applied: read-only, and {@code max_execution_time} set. Close it to hand
     * the connection back with both reset — the pool is shared with schema
     * reads, which must not inherit a five-second limit.
     *
     * @throws ServerOfflineException while the environment is served from its cache
     */
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

    /** A pooled connection whose session could not be set, or reset, is not given back to anyone else. */
    private static void discard(DataSource dataSource, Connection connection) {
        if (dataSource instanceof HikariDataSource hikari) {
            hikari.evictConnection(connection);
        } else {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // nothing more to do with it
            }
        }
    }

    /**
     * One sampling session. {@link #connection()} is read-only and
     * statement-time-limited for as long as the session is open; row caps are
     * the caller's ({@link SamplingLimits#maxRows()}).
     */
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
                // the pool reclaims it
            }
        }
    }
}
