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

/**
 * Runs {@code EXPLAIN} against an environment's own servers.
 *
 * <p>This is the only place in the application that executes a statement of our
 * choosing against an analyzed database. Everything else reads the server's own
 * bookkeeping — schema metadata, {@code performance_schema}. That difference is
 * why the four rules below are structural rather than stylistic, and why each
 * one is enforced here rather than left to callers to remember.
 *
 * <ol>
 *   <li><b>The environment must opt in.</b> {@code explainEnabled} defaults to
 *       false, so a config that does not mention it cannot enable it.</li>
 *   <li><b>The statement text never comes from the request.</b> Callers pass an
 *       identifier; the text is read from storage. There is no code path from a
 *       request body to the database.</li>
 *   <li><b>{@code EXPLAIN ANALYZE} is refused.</b> It is not a planning
 *       operation — MySQL runs the statement and reports actual timings, which
 *       is exactly what this feature must never do. Verified against 8.4.4
 *       rather than assumed.</li>
 *   <li><b>Every statement is time-boxed</b> via {@link Statement#setQueryTimeout}.</li>
 * </ol>
 */
@Service
public class LiveExplainService {

    private static final Logger log = LoggerFactory.getLogger(LiveExplainService.class);

    /**
     * Planning a statement is cheap; anything that takes longer than this is a
     * server in trouble, and holding a connection open against an analyzed
     * production database is the last thing this feature should do.
     */
    static final int QUERY_TIMEOUT_SECONDS = 10;

    private final EnvironmentContext environmentContext;

    public LiveExplainService(EnvironmentContext environmentContext) {
        this.environmentContext = environmentContext;
    }

    public enum Failure {
        /** The active environment has not set {@code explainEnabled: true}. */
        NOT_ENABLED,
        /** The environment has no server to run against. */
        NO_SERVER,
        /** The environment is served from its cache and connects to nothing until pulled (sqlx-nbi.4). */
        OFFLINE,
        /** The statement is not one this may run — see {@link #assertExplainable}. */
        REFUSED,
        /** The server rejected or failed the statement. */
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

    /**
     * Explain {@code sql}, which the caller must have read from storage rather
     * than accepted from a request.
     *
     * @param sql           a complete, runnable statement — placeholders will not
     *                      do, see {@link #assertExplainable}
     * @param sessionSchema the schema the statement was digested under, used as
     *                      the connection's default database. Not optional in
     *                      practice: an unqualified statement means different
     *                      things in different schemas, and a connection with no
     *                      default database answers "No database selected"
     *                      rather than planning anything. This is the same value
     *                      the digest was scoped by, so planning happens in the
     *                      scope the statement was identified in.
     */
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
        // The first server, and the response says which. An environment with
        // several replicas will plan the same statement the same way on each;
        // where it would not, the caller needs to know which one answered.
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
     * Point the connection at the schema the statement was digested under.
     *
     * <p>Both calls, because Connector/J's {@code databaseTerm} decides which
     * one means anything: under {@code CATALOG} (the default) {@code setSchema}
     * is a no-op, and under {@code SCHEMA} it is {@code setCatalog} that does
     * nothing. Neither throws when it is the inert one. Doing both is how this
     * works regardless of how the URL was configured.
     *
     * <p>Via JDBC rather than a {@code USE} statement so the schema name stays
     * out of the SQL string entirely — it is data, and this is the one class
     * that talks to an analyzed server.
     */
    private static void selectDatabase(Connection connection, String sessionSchema) throws SQLException {
        if (sessionSchema == null || sessionSchema.isBlank()) {
            return;
        }
        connection.setCatalog(sessionSchema);
        try {
            connection.setSchema(sessionSchema);
        } catch (SQLException | UnsupportedOperationException e) {
            // driver does not model schemas separately; setCatalog above was the
            // meaningful call and it has already run
            log.debug("setSchema not supported, relying on setCatalog", e);
        }
    }

    /**
     * Whether this statement may be sent to a server at all.
     *
     * @return null when it may, or the reason it may not
     */
    static String assertExplainable(String sql) {
        if (sql == null || sql.isBlank()) {
            return "no statement to explain";
        }
        String trimmed = sql.strip();
        String upper = trimmed.toUpperCase(Locale.ROOT);

        // EXPLAIN ANALYZE *runs* the statement — measured on 8.4.4, it reports
        // "actual time=" — so it is refused however it arrives, including
        // already prefixed by a caller.
        if (upper.startsWith("EXPLAIN")) {
            return "the statement is already an EXPLAIN; this endpoint adds its own";
        }
        if (upper.contains("ANALYZE")) {
            return "ANALYZE executes the statement rather than planning it";
        }

        // A second statement smuggled in after a semicolon would run unplanned.
        // Trailing semicolons are fine; anything after one is not.
        int semicolon = trimmed.indexOf(';');
        if (semicolon >= 0 && !trimmed.substring(semicolon + 1).isBlank()) {
            return "more than one statement was supplied";
        }

        // The corpus stores MySQL's digest text, whose values are `?`. That is
        // a syntax error to EXPLAIN — verified on 8.4.4, error 1064 — so it is
        // refused here with something more useful than the server's complaint.
        if (trimmed.contains("?")) {
            return "this statement still has ? placeholders and cannot be planned; "
                + "add a query example with real parameters and explain that instead";
        }
        return null;
    }
}
