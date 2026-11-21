package com.github.safetytrick.sqlexplorer.indexes;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.github.safetytrick.sqlexplorer.coverage.Coverage;
import com.github.safetytrick.sqlexplorer.environment.DatabaseServer;
import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * THE WINDOW CANNOT BE MEASURED, ONLY BOUNDED. These are Performance Schema
 * counters, and Performance Schema can be truncated without restarting the
 * server — so uptime is an upper bound on how long a connection has been
 * observing, never the observation itself. That matters more here than
 * anywhere: a truncate zeroes {@code count_star} for every index at once, so
 * immediately afterwards the entire environment reads as unused. Not a degraded
 * answer — the maximally wrong one, and the one that ends in DROP INDEX.
 */
@Service
public class UnusedIndexService {

    private static final Logger log = LoggerFactory.getLogger(UnusedIndexService.class);

    private static final String UNUSED =
        "select object_schema, object_name, index_name from sys.schema_unused_indexes"
            + " where object_schema not in ('mysql', 'performance_schema', 'information_schema', 'sys')";

    /**
     * {@link #UNUSED} without the sys schema (sqlx-jip): the view's own
     * definition, as MySQL 8.4 ships it, read straight from the tables it
     * selects from. Some servers have no {@code sys} schema, or a user who
     * cannot see it; without this they could not be read at all. Checked
     * against the view on a live server: the same rows.
     */
    static final String UNUSED_WITHOUT_SYS =
        "select t.object_schema, t.object_name, t.index_name"
            + " from performance_schema.table_io_waits_summary_by_index_usage t"
            + " join information_schema.statistics s on t.object_schema = s.table_schema"
            + " and t.object_name = s.table_name and t.index_name = s.index_name"
            + " where t.index_name is not null and t.count_star = 0 and t.index_name <> 'PRIMARY'"
            + " and s.non_unique = 1 and s.seq_in_index = 1"
            + " and t.object_schema not in ('mysql', 'performance_schema', 'information_schema', 'sys')";

    static final String WITHOUT_SYS_NOTE =
        "no sys.schema_unused_indexes here; read from performance_schema directly";

    private static final String READ =
        "select object_schema, object_name, index_name"
            + " from performance_schema.table_io_waits_summary_by_index_usage"
            + " where index_name is not null and count_star > 0";

    private static final String UPTIME = "show global status like 'Uptime'";

    private final EnvironmentRegistry registry;
    private final long minimumDays;
    private final Map<String, UnusedIndexes.Report> latest = new ConcurrentHashMap<>();
    private final UnusedIndexReportStore store;

    @Autowired
    public UnusedIndexService(EnvironmentRegistry registry, UnusedIndexReportStore store,
                              @Value("${sql-explorer.unused-index-minimum-days:7}") long minimumDays) {
        this.registry = registry;
        this.store = store;
        this.minimumDays = minimumDays;
    }

    UnusedIndexService(EnvironmentRegistry registry, long minimumDays) {
        this(registry, null, minimumDays);
    }

    public Optional<UnusedIndexes.Report> latest(String environmentName) {
        String name = registry.getOrDefault(environmentName).getName();
        UnusedIndexes.Report report = latest.get(name);
        if (report == null && store != null) {
            report = store.read(name).orElse(null);
            if (report != null) { latest.putIfAbsent(name, report); }
        }
        return Optional.ofNullable(report);
    }

    public UnusedIndexes.Report report(String environmentName) {
        UnusedIndexes.Report report = readEnvironment(registry.getOrDefault(environmentName));
        latest.put(report.environment(), report);
        if (store != null) { store.write(report); }
        return report;
    }

    private UnusedIndexes.Report readEnvironment(Environment environment) {
        Instant started = Instant.now();

        Set<UnusedIndexes.Index> candidates = new LinkedHashSet<>();
        Set<UnusedIndexes.Index> read = new LinkedHashSet<>();
        List<UnusedIndexes.Connection> connections = new ArrayList<>();
        boolean complete = true;

        for (DatabaseServer server : environment.getServers()) {
            try {
                Reading reading = read(server);

                candidates.addAll(reading.unused());
                read.addAll(reading.read());
                connections.add(new UnusedIndexes.Connection(server.getName(),
                    verdictFor(reading.upSeconds()), null, reading.note(), reading.upSeconds(),
                    reading.unused().size(), reading.read().size()));
            } catch (RuntimeException e) {
                complete = false;
                if (e instanceof com.github.safetytrick.sqlexplorer.environment.ServerOfflineException) {
                    log.debug("index usage of {} not read: {}", server.getName(), e.getMessage());
                } else {
                    log.warn("could not read index usage from {}: {}", server.getName(), e.toString());
                }
                connections.add(new UnusedIndexes.Connection(server.getName(),
                    Coverage.Verdict.UNKNOWN, e.getMessage(), null, null, 0, 0));
            }
        }

        List<UnusedIndexes.Index> unused = candidates.stream()
            .filter(index -> !read.contains(index))
            .sorted(Comparator.comparing(UnusedIndexes.Index::schema)
                        .thenComparing(UnusedIndexes.Index::table)
                        .thenComparing(UnusedIndexes.Index::name))
            .toList();

        Instant finished = Instant.now();
        return new UnusedIndexes.Report(environment.getName(), List.copyOf(connections),
            complete, unused, basis(connections, complete, unused.size()), finished,
            Duration.between(started, finished).toMillis(), minimumDays);
    }

    record Reading(List<UnusedIndexes.Index> unused, List<UnusedIndexes.Index> read, Long upSeconds, String note) {
        Reading(List<UnusedIndexes.Index> unused, List<UnusedIndexes.Index> read, Long upSeconds) {
            this(unused, read, upSeconds, null);
        }
    }

    record Unused(List<UnusedIndexes.Index> indexes, String note) {
    }

    static Unused unused(Function<String, List<UnusedIndexes.Index>> run) {
        try {
            return new Unused(run.apply(UNUSED), null);
        } catch (BadSqlGrammarException e) {
            return new Unused(run.apply(UNUSED_WITHOUT_SYS), WITHOUT_SYS_NOTE);
        }
    }

    Reading read(DatabaseServer server) {
        JdbcTemplate jdbc = new JdbcTemplate(server.getDataSource());
        Unused unused = unused(sql -> query(jdbc, sql));
        return new Reading(unused.indexes(), query(jdbc, READ), uptime(jdbc), unused.note());
    }

    private static List<UnusedIndexes.Index> query(JdbcTemplate jdbc, String sql) {
        return jdbc.query(sql, (rs, row) ->
            new UnusedIndexes.Index(rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    private static Long uptime(JdbcTemplate jdbc) {
        List<Long> seconds = jdbc.query(UPTIME, (rs, row) -> {
            try {
                return Long.valueOf(rs.getString(2));
            } catch (NumberFormatException e) {
                return null;
            }
        });
        return seconds.isEmpty() ? null : seconds.getFirst();
    }

    private Coverage.Verdict verdictFor(Long upSeconds) {
        if (upSeconds == null) {
            return Coverage.Verdict.UNKNOWN;
        }
        return Duration.ofSeconds(upSeconds).toDays() >= minimumDays
            ? Coverage.Verdict.OK
            : Coverage.Verdict.IMPAIRED;
    }

    private String basis(List<UnusedIndexes.Connection> connections, boolean complete, int found) {
        if (connections.isEmpty()) {
            return "This environment has no connections to read.";
        }
        StringBuilder basis = new StringBuilder();
        if (!complete) {
            basis.append("At least one connection could not be read, so this is a statement about "
                + "the connections that answered and not about the environment. ");
        }
        basis.append(found).append(found == 1 ? " index has" : " indexes have")
            .append(" no recorded reads on any connection that answered. ");
        long shortest = connections.stream().filter(c -> c.upSeconds() != null)
            .mapToLong(UnusedIndexes.Connection::upSeconds).min().orElse(-1);
        if (shortest >= 0) {
            basis.append("The shortest connection has been up ")
                .append(Duration.ofSeconds(shortest).toDays())
                .append(" days, which is an UPPER BOUND on how long it has been counting: "
                    + "Performance Schema can be truncated without restarting the server, and a "
                    + "truncate zeroes every index at once — so soon after one, everything here "
                    + "would read as unused.");
        }
        return basis.toString().trim();
    }
}
