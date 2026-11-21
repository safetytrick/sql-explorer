package com.github.safetytrick.sqlexplorer.ddl;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.github.safetytrick.sqlexplorer.environment.DatabaseServer;
import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.ServerOfflineException;
import com.github.safetytrick.sqlexplorer.environment.ServerRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LockHolderService {

    private static final Logger log = LoggerFactory.getLogger(LockHolderService.class);

    static final int MAX_TABLES = 200;
    static final int MAX_SERVERS = 32;
    static final int QUERY_TIMEOUT_SECONDS = 5;

    private static final String HOLDERS_SELECT =
        "select ml.object_schema, ml.object_name, ml.lock_type, ml.lock_duration, ml.lock_status,"
            + " t.processlist_id, t.processlist_user, t.processlist_host, t.processlist_command,"
            + " t.processlist_state, t.processlist_time";

    private static final String HOLDERS_FROM =
        " from performance_schema.metadata_locks ml"
            + " join performance_schema.threads t on t.thread_id = ml.owner_thread_id";

    /** Transaction age needs PROCESS, which a monitoring user may lack; the locks do not. */
    private static final String WITH_TRX_SELECT =
        ", timestampdiff(second, trx.trx_started, now())";

    private static final String WITH_TRX_FROM =
        " left join information_schema.innodb_trx trx on trx.trx_mysql_thread_id = t.processlist_id";

    static final String WITHOUT_TRX_NOTE =
        "transaction age unavailable (information_schema.INNODB_TRX needs the PROCESS privilege); "
            + "time in current state shown instead";

    public record TableRef(String schema, String table) { }

    public record Holder(String schema, String table, String lockType, String duration, String status,
                         Long processlistId, String user, String host, String command, String state,
                         Long secondsInState, Long transactionSeconds) { }

    public record ServerReading(String server, List<String> roles, String error, String note,
                                List<Holder> holders) { }

    public record Report(String environment, Instant readAt, long tookMillis, List<TableRef> tables,
                        List<ServerReading> servers) { }

    public static class InvalidRequest extends IllegalArgumentException {
        InvalidRequest(String message) {
            super(message);
        }
    }

    public Report read(Environment environment, List<TableRef> tables, List<String> serverNames) {
        List<TableRef> wanted = validTables(tables);
        List<DatabaseServer> servers = namedServers(environment, serverNames);

        Instant started = Instant.now();
        List<ServerReading> readings = new ArrayList<>(servers.size());
        for (DatabaseServer server : servers) {
            List<String> roles = server.getRoles().stream().map(ServerRole::configName).sorted().toList();
            try {
                Reading reading = read(server, wanted);
                readings.add(new ServerReading(server.getName(), roles, null, reading.note(),
                    sorted(reading.holders())));
            } catch (ServerOfflineException e) {
                readings.add(new ServerReading(server.getName(), roles, e.getMessage(), null, List.of()));
            } catch (RuntimeException e) {
                log.warn("could not read metadata locks from {}: {}", server.getName(), e.toString());
                readings.add(new ServerReading(server.getName(), roles, rootMessage(e), null, List.of()));
            }
        }
        Instant finished = Instant.now();
        return new Report(environment.getName(), finished, Duration.between(started, finished).toMillis(),
            wanted, List.copyOf(readings));
    }

    record Reading(List<Holder> holders, String note) { }

    Reading read(DatabaseServer server, List<TableRef> tables) {
        JdbcTemplate jdbc = new JdbcTemplate(server.getDataSource());
        jdbc.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        return holders(tables, (sql, args) -> jdbc.query(sql, (rs, row) -> new Holder(
            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
            longOrNull(rs.getObject(6)), rs.getString(7), rs.getString(8), rs.getString(9),
            rs.getString(10), longOrNull(rs.getObject(11)),
            rs.getMetaData().getColumnCount() >= 12 ? longOrNull(rs.getObject(12)) : null), args));
    }

    interface Runner {
        List<Holder> run(String sql, Object[] args);
    }

    static Reading holders(List<TableRef> tables, Runner runner) {
        Object[] args = tables.stream()
            .flatMap(t -> java.util.stream.Stream.of(t.schema(), t.table()))
            .toArray();
        String where = where(tables.size());
        try {
            return new Reading(runner.run(HOLDERS_SELECT + WITH_TRX_SELECT + HOLDERS_FROM + WITH_TRX_FROM
                + where, args), null);
        } catch (DataAccessException e) {
            if (!refused(e)) {
                throw e;
            }
            return new Reading(runner.run(HOLDERS_SELECT + HOLDERS_FROM + where, args), WITHOUT_TRX_NOTE);
        }
    }

    static String where(int tables) {
        String pairs = String.join(" or ",
            java.util.Collections.nCopies(tables, "(ml.object_schema = ? and ml.object_name = ?)"));
        return " where ml.object_type = 'TABLE' and (" + pairs + ")"
            + " and (t.processlist_id is null or t.processlist_id <> connection_id())";
    }

    /** MySQL's access-denied errors: 1227 (needs a privilege), 1142 and 1044 (no access). */
    private static boolean refused(DataAccessException e) {
        Throwable cause = e.getMostSpecificCause();
        if (cause instanceof java.sql.SQLException sql) {
            int code = sql.getErrorCode();
            return code == 1227 || code == 1142 || code == 1044;
        }
        return false;
    }

    private static List<TableRef> validTables(List<TableRef> tables) {
        if (tables == null || tables.isEmpty()) {
            throw new InvalidRequest("name at least one table");
        }
        Set<TableRef> unique = new LinkedHashSet<>();
        for (TableRef table : tables) {
            if (table == null || blank(table.schema()) || blank(table.table())) {
                throw new InvalidRequest("every table needs a schema and a name");
            }
            unique.add(new TableRef(table.schema(), table.table()));
        }
        if (unique.size() > MAX_TABLES) {
            throw new InvalidRequest("at most " + MAX_TABLES + " tables at once");
        }
        return List.copyOf(unique);
    }

    private static List<DatabaseServer> namedServers(Environment environment, List<String> names) {
        if (names == null || names.isEmpty()) {
            throw new InvalidRequest("choose at least one server");
        }
        Set<String> wanted = new LinkedHashSet<>(names);
        if (wanted.size() > MAX_SERVERS) {
            throw new InvalidRequest("at most " + MAX_SERVERS + " servers at once");
        }
        Map<String, DatabaseServer> byName = environment.getServers().stream()
            .collect(Collectors.toMap(DatabaseServer::getName, Function.identity(), (a, b) -> a));
        List<String> unknown = wanted.stream().filter(name -> !byName.containsKey(name)).toList();
        if (!unknown.isEmpty()) {
            throw new InvalidRequest("not a server of " + environment.getName() + ": " + String.join(", ", unknown));
        }
        return environment.getServers().stream().filter(server -> wanted.contains(server.getName())).toList();
    }

    private static List<Holder> sorted(List<Holder> holders) {
        return holders.stream()
            .sorted(Comparator.comparing(Holder::transactionSeconds, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Holder::secondsInState, Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static Long longOrNull(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : e.toString();
    }
}
