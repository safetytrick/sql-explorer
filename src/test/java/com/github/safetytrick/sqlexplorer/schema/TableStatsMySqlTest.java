package com.github.safetytrick.sqlexplorer.schema;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.safetytrick.sqlexplorer.environment.ConnectionInfo;
import com.github.safetytrick.sqlexplorer.environment.DatabaseServer;
import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.SamplingTarget;
import com.github.safetytrick.sqlexplorer.environment.ServerRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Against a real MySQL: the one {@code information_schema.TABLES} query
 * (sqlx-n5j.2), and the limits a sampling session puts on its connection —
 * and takes off again before the pool hands it to a schema read (sqlx-n5j.4).
 */
@Testcontainers
class TableStatsMySqlTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.4");

    private static DatabaseServer server;

    @BeforeAll
    static void schema() throws SQLException {
        server = new DatabaseServer("app-replica",
            new ConnectionInfo(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()), List.of(),
            mock(SchemaRepositoryFactory.class), null, Set.of(ServerRole.REPLICA, ServerRole.SAMPLING));
        try (Connection connection = server.getDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE account (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(64)) "
                + "ROW_FORMAT=DYNAMIC");
            statement.execute("INSERT INTO account (name) VALUES ('a'), ('b'), ('c')");
            statement.execute("CREATE VIEW account_names AS SELECT name FROM account");
            statement.execute("ANALYZE TABLE account");
        }
    }

    @AfterAll
    static void close() {
        server.close();
    }

    @Test
    void oneQueryReadsEveryBaseTablesFigures() {
        Map<String, TableStats> stats = new TableStatsService().read(server.getDataSource(), "app-replica");

        String account = SchemaFingerprint.key(MYSQL.getDatabaseName(), "account");
        assertThat(stats).containsKey(account)
            .doesNotContainKey(SchemaFingerprint.key(MYSQL.getDatabaseName(), "account_names"));
        assertThat(stats.keySet()).noneMatch(key -> key.startsWith("mysql.") || key.startsWith("sys."));
        TableStats figures = stats.get(account);
        assertThat(figures.server()).isEqualTo("app-replica");
        assertThat(figures.readAt()).isNotNull();
        assertThat(figures.engine()).isEqualTo("InnoDB");
        assertThat(figures.rowFormat()).isEqualTo("Dynamic");
        assertThat(figures.tableCollation()).isNotBlank();
        assertThat(figures.rowsEstimate()).isNotNull();
        assertThat(figures.dataLength()).isPositive();
        assertThat(figures.indexLength()).isNotNull();
        assertThat(figures.autoIncrement()).isEqualTo(4L);
        assertThat(figures.createTime()).isNotNull();
    }

    @Test
    void aSamplingSessionIsReadOnlyAndTimeLimitedUntilClosed() throws SQLException {
        SamplingTarget target = new Environment("production", List.of(server)).samplingTarget();

        try (SamplingTarget.SamplingSession session = target.openSession()) {
            assertThat(variable(session.connection(), "transaction_read_only")).isEqualTo("1");
            assertThat(variable(session.connection(), "max_execution_time"))
                .isEqualTo(String.valueOf(target.limits().maxExecutionTime().toMillis()));
            assertThatThrownBy(() -> {
                try (Statement statement = session.connection().createStatement()) {
                    statement.execute("INSERT INTO account (name) VALUES ('d')");
                }
            }).as("a write is refused").isInstanceOf(SQLException.class);
        }

        // the pool is shared with schema reads, which must not inherit either limit
        for (int i = 0; i < 4; i++) {
            try (Connection connection = server.getDataSource().getConnection()) {
                assertThat(variable(connection, "transaction_read_only")).isEqualTo("0");
                assertThat(variable(connection, "max_execution_time")).isEqualTo("0");
            }
        }
    }

    private static String variable(Connection connection, String name) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT @@SESSION." + name)) {
            row.next();
            return row.getString(1);
        }
    }
}
