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
 * Which indexes no connection in an environment has read.
 *
 * <p>THE RULE IS "READ NOWHERE", NOT "UNUSED EVERYWHERE", and the difference is
 * the whole design. An index is reported when some connection lists it as
 * unused and NO connection has recorded a read of it. Intersecting the unused
 * sets instead would drop an index that exists on only one server — common the
 * moment an environment's connections do not hold identical schemas — while
 * disqualifying on any recorded read keeps the property that actually matters:
 * a replica serving reports from an index the primary never touches will veto
 * it (sqlx-vjr).
 *
 * <p>A CONNECTION THAT COULD NOT BE READ IS NOT AGREEMENT. It contributes
 * neither candidates nor vetoes, and the report says it is incomplete and names
 * it. Counting an unreachable replica as silent assent turns a broken
 * connection into a recommendation to drop an index.
 *
 * <p>THE WINDOW CANNOT BE MEASURED, ONLY BOUNDED. These are Performance Schema
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

    /**
     * Indexes the server lists as never read, already excluding primary and
     * unique keys — the sys view filters those upstream, and reimplementing the
     * filter here would be a second definition of the same thing.
     *
     * <p>THE SERVER'S OWN SCHEMAS ARE EXCLUDED, and this is not the hardcoded
     * system-schema list that sqlx-g7z removed from {@code /unqueried}. That one
     * hid schemas a deployment might legitimately care about, by name, where a
     * tag would have done. These are schemas whose indexes CANNOT BE DROPPED BY
     * ANYONE — verified: {@code DROP INDEX} on a performance_schema table is
     * refused even for root. Listing them on a report whose only action is
     * DROP INDEX is not noise, it is wrong output; measured here, 43 of 51
     * findings were performance_schema and none of them were actionable.
     */
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

    /**
     * Indexes this server HAS recorded reads against.
     *
     * <p>The veto set, and deliberately not the complement of the query above:
     * it includes primary and unique keys, and an index absent from a server
     * simply produces no row, so a server that does not have an index cannot
     * veto it.
     */
    private static final String READ =
        "select object_schema, object_name, index_name"
            + " from performance_schema.table_io_waits_summary_by_index_usage"
            + " where index_name is not null and count_star > 0";

    private static final String UPTIME = "show global status like 'Uptime'";

    private final EnvironmentRegistry registry;
    private final long minimumDays;
    /**
     * The last report read for each environment (sqlx-1bo.2). Reading connects
     * to every server in the environment, so the page shows this instead of
     * reading on every visit, and reads again only when asked. Kept on disk by
     * {@link UnusedIndexReportStore}, so a restart does not forget it; this is
     * the copy in hand.
     */
    private final Map<String, UnusedIndexes.Report> latest = new ConcurrentHashMap<>();
    /** Null keeps reports in memory only, as the tests do. */
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

    /** The report last read for this environment, from before a restart too; empty if it never has been. */
    public Optional<UnusedIndexes.Report> latest(String environmentName) {
        String name = registry.getOrDefault(environmentName).getName();
        UnusedIndexes.Report report = latest.get(name);
        if (report == null && store != null) {
            report = store.read(name).orElse(null);
            if (report != null) { latest.putIfAbsent(name, report); }
        }
        return Optional.ofNullable(report);
    }

    /** Read every connection of the environment now, and keep the answer as its latest, on disk too. */
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
                // Named, not counted. Silence from a connection is not evidence
                // that an index is unused on it.
                complete = false;
                if (e instanceof com.github.safetytrick.sqlexplorer.environment.ServerOfflineException) {
                    // cached environment: nothing was tried, so nothing failed (sqlx-nbi.4)
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

    /**
     * What one connection says about its indexes.
     *
     * @param unused    what it lists as never read
     * @param read      what it has recorded reads against — the veto set
     * @param upSeconds how long it has been up, an upper bound on the window
     * @param note      how it was read, when not the usual way, or null
     */
    record Reading(List<UnusedIndexes.Index> unused, List<UnusedIndexes.Index> read, Long upSeconds, String note) {
        Reading(List<UnusedIndexes.Index> unused, List<UnusedIndexes.Index> read, Long upSeconds) {
            this(unused, read, upSeconds, null);
        }
    }

    /** The unused half of a reading, and how it was read. */
    record Unused(List<UnusedIndexes.Index> indexes, String note) {
    }

    /**
     * The never-read indexes, through the sys view where there is one and
     * from performance_schema where there is not (sqlx-jip). Only a grammar
     * error falls back, which is how a missing view or schema answers: a lost
     * connection or a refused grant must still fail the read, not be retried
     * as something else.
     */
    static Unused unused(Function<String, List<UnusedIndexes.Index>> run) {
        try {
            return new Unused(run.apply(UNUSED), null);
        } catch (BadSqlGrammarException e) {
            return new Unused(run.apply(UNUSED_WITHOUT_SYS), WITHOUT_SYS_NOTE);
        }
    }

    /**
     * Read one connection.
     *
     * <p>The seam a test drives, deliberately above JDBC rather than at it: what
     * needs proving is how several connections' answers COMBINE — which vetoes
     * which, and what an unreadable one means — and none of that is about SQL.
     * The statements themselves are verified against a live server instead,
     * which is the only place they could be verified honestly.
     */
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

    /**
     * OK only when the server has been up long enough for silence to mean
     * something. Below that the reading is reported as IMPAIRED rather than
     * withheld — the reader should see that the connection answered, and that
     * its answer cannot carry the weight of a deletion.
     */
    private Coverage.Verdict verdictFor(Long upSeconds) {
        if (upSeconds == null) {
            return Coverage.Verdict.UNKNOWN;
        }
        return Duration.ofSeconds(upSeconds).toDays() >= minimumDays
            ? Coverage.Verdict.OK
            : Coverage.Verdict.IMPAIRED;
    }

    /**
     * The sentence a reader needs before acting, assembled from what was
     * actually observed rather than stated once in the UI and left to drift.
     */
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
