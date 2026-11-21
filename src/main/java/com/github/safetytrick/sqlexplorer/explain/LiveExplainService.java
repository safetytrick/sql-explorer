package com.github.safetytrick.sqlexplorer.explain;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

import com.github.safetytrick.sqlexplorer.environment.DatabaseServer;
import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LiveExplainService {

    private static final Logger log = LoggerFactory.getLogger(LiveExplainService.class);

    static final int QUERY_TIMEOUT_SECONDS = 10;

    private final EnvironmentContext environmentContext;

    public LiveExplainService(EnvironmentContext environmentContext) {
        this.environmentContext = environmentContext;
    }

    public enum Failure {
        NOT_ENABLED,
        NO_SERVER,
        OFFLINE,
        REFUSED,
        EXECUTION_FAILED
    }

    public record Result(String plan, String serverName, Failure failure, String detail) {

        static Result ok(String plan, String serverName) {
            return new Result(plan, serverName, null, null);
        }

        static Result failed(Failure failure, String detail) {
            return new Result(null, null, failure, detail);
        }

        public boolean succeeded() {
            return plan != null;
        }
    }

    public Result explain(String sql, String sessionSchema) {
        Environment environment = environmentContext.current();
        if (environment == null) {
            return Result.failed(Failure.NO_SERVER, "no active environment");
        }
        if (!environment.isExplainEnabled()) {
            return Result.failed(Failure.NOT_ENABLED,
                "environment '" + environment.getName() + "' has not enabled EXPLAIN; "
                    + "set environments." + environment.getName() + ".explainEnabled: true to allow it");
        }

        if (environment.isCached()) {
            return Result.failed(Failure.OFFLINE,
                "environment '" + environment.getName() + "' is served from its cache and is not connected; "
                    + "pull it to go live, then EXPLAIN");
        }

        String refusal = assertExplainable(sql);
        if (refusal != null) {
            return Result.failed(Failure.REFUSED, refusal);
        }

        List<DatabaseServer> servers = environment.getServers();
        if (servers.isEmpty()) {
            return Result.failed(Failure.NO_SERVER,
                "environment '" + environment.getName() + "' has no servers");
        }
        DatabaseServer server = servers.getFirst();

        String explainSql = "EXPLAIN FORMAT=JSON " + sql;
        try (Connection connection = server.getDataSource().getConnection()) {
            selectDatabase(connection, sessionSchema);
            try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet resultSet = statement.executeQuery(explainSql)) {
                if (!resultSet.next()) {
                    return Result.failed(Failure.EXECUTION_FAILED, "the server returned no plan");
                }
                return Result.ok(resultSet.getString(1), server.getName());
            }
            }
        } catch (SQLException e) {
            log.debug("EXPLAIN failed on {}", server.getName(), e);
            return Result.failed(Failure.EXECUTION_FAILED, e.getMessage());
        }
    }

    /**
     * Both calls, because Connector/J's {@code databaseTerm} decides which
     * one means anything: under {@code CATALOG} (the default) {@code setSchema}
     * is a no-op, and under {@code SCHEMA} it is {@code setCatalog} that does
     * nothing. Neither throws when it is the inert one. Doing both is how this
     * works regardless of how the URL was configured.
     */
    private static void selectDatabase(Connection connection, String sessionSchema) throws SQLException {
        if (sessionSchema == null || sessionSchema.isBlank()) {
            return;
        }
        connection.setCatalog(sessionSchema);
        try {
            connection.setSchema(sessionSchema);
        } catch (SQLException | UnsupportedOperationException e) {
            log.debug("setSchema not supported, relying on setCatalog", e);
        }
    }

    static String assertExplainable(String sql) {
        if (sql == null || sql.isBlank()) {
            return "no statement to explain";
        }
        String trimmed = sql.strip();
        String upper = trimmed.toUpperCase(Locale.ROOT);

        if (upper.startsWith("EXPLAIN")) {
            return "the statement is already an EXPLAIN; this endpoint adds its own";
        }
        if (upper.contains("ANALYZE")) {
            return "ANALYZE executes the statement rather than planning it";
        }

        int semicolon = trimmed.indexOf(';');
        if (semicolon >= 0 && !trimmed.substring(semicolon + 1).isBlank()) {
            return "more than one statement was supplied";
        }

        if (trimmed.contains("?")) {
            return "this statement still has ? placeholders and cannot be planned; "
                + "add a query example with real parameters and explain that instead";
        }
        return null;
    }
}
