package com.github.safetytrick.sqlexplorer.indexes;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.coverage.Coverage;
import com.github.safetytrick.sqlexplorer.environment.ConnectionInfo;
import com.github.safetytrick.sqlexplorer.environment.DatabaseServer;
import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which indexes an environment can honestly be said to have stopped reading.
 *
 * <p>THIS IS THE ONE REPORT THAT ENDS IN A DELETION, so the tests are about the
 * ways it could wrongly say yes: a replica whose reads are not counted, an
 * unreachable connection read as agreement, and a server that has not been
 * counting long enough for silence to mean anything (sqlx-vjr).
 */
class UnusedIndexServiceTest {

    private static final long UP_LONG_ENOUGH = 30 * 24 * 3600L;
    private static final long UP_AN_HOUR = 3600L;

    /** What one fake connection will answer. */
    private record Answers(List<UnusedIndexes.Index> unused, List<UnusedIndexes.Index> read, Long up) { }

    /** An ordered map, so the connections are read in a predictable order. */
    private static Map<String, Answers> ordered(Map.Entry<String, Answers> first,
                                                Map.Entry<String, Answers> second) {
        Map<String, Answers> map = new java.util.LinkedHashMap<>();
        map.put(first.getKey(), first.getValue());
        map.put(second.getKey(), second.getValue());
        return map;
    }

    private static Map.Entry<String, Answers> on(String server, Answers answers) {
        return Map.entry(server, answers);
    }

    private static UnusedIndexes.Index index(String table, String name) {
        return new UnusedIndexes.Index("app", table, name);
    }

    /**
     * A service whose connections answer from a script rather than a server.
     *
     * <p>Driven at {@code read(server)} rather than at JDBC: what these tests
     * prove is how several connections' answers COMBINE, and none of that is
     * about SQL. A null entry means that connection refuses, which is one of
     * the cases that has to be right.
     */
    private UnusedIndexService serviceOver(Map<String, Answers> byServer) {
        List<DatabaseServer> servers = byServer.keySet().stream()
            .map(name -> new DatabaseServer(name,
                new ConnectionInfo("jdbc:mysql://nowhere", "u", "p"), List.of(),
                mock(SchemaRepositoryFactory.class)))
            .toList();
        EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
        when(registry.getOrDefault(anyString())).thenReturn(new Environment("prod", servers));

        return new UnusedIndexService(registry, 7) {
            @Override
            Reading read(DatabaseServer server) {
                Answers answers = byServer.get(server.getName());
                if (answers == null) {
                    throw new IllegalStateException("connection refused");
                }
                return new Reading(answers.unused(), answers.read(), answers.up());
            }
        };
    }

    /** The simple case: one connection, nothing has read it. */
    @Test
    void anIndexNoConnectionHasReadIsReported() {
        UnusedIndexes.Report report = serviceOver(Map.of(
            "primary", new Answers(List.of(index("orders", "idx_old")), List.of(), UP_LONG_ENOUGH)
        )).report("prod");

        assertThat(report.unused()).containsExactly(index("orders", "idx_old"));
        assertThat(report.complete()).isTrue();
    }

    /**
     * THE CASE THE WHOLE FEATURE IS FOR. The primary never touches it; a replica
     * serves reports from it. Reporting it would be recommending the deletion of
     * an index something depends on.
     */
    @Test
    void anIndexOneConnectionReadsIsNotReportedEvenIfAnotherCallsItUnused() {
        UnusedIndexes.Report report = serviceOver(ordered(
            on("primary", new Answers(List.of(index("orders", "idx_reports")), List.of(), UP_LONG_ENOUGH)),
            on("replica", new Answers(List.of(), List.of(index("orders", "idx_reports")), UP_LONG_ENOUGH))
        )).report("prod");

        assertThat(report.unused()).isEmpty();
        assertThat(report.complete()).isTrue();
    }

    /**
     * An index only one connection HAS is still reportable. Intersecting the
     * unused sets would drop it — which is why the rule is "read nowhere"
     * rather than "unused everywhere".
     */
    @Test
    void anIndexOnlyOneConnectionHasIsStillReported() {
        UnusedIndexes.Report report = serviceOver(ordered(
            on("primary", new Answers(List.of(index("orders", "idx_only_here")), List.of(), UP_LONG_ENOUGH)),
            on("replica", new Answers(List.of(), List.of(index("other", "idx_elsewhere")), UP_LONG_ENOUGH))
        )).report("prod");

        assertThat(report.unused()).containsExactly(index("orders", "idx_only_here"));
    }

    /**
     * SILENCE IS NOT AGREEMENT. A connection that could not be read contributes
     * no veto, so the report must say it is not a statement about the
     * environment — otherwise a broken replica becomes a reason to drop.
     */
    @Test
    void aConnectionThatCouldNotBeReadIsNamedRatherThanCountedAsAgreement() {
        Map<String, Answers> servers = new java.util.LinkedHashMap<>();
        servers.put("primary", new Answers(List.of(index("orders", "idx_old")), List.of(), UP_LONG_ENOUGH));
        servers.put("replica", null);

        UnusedIndexes.Report report = serviceOver(servers).report("prod");

        assertThat(report.complete()).as("one connection did not answer").isFalse();
        assertThat(report.connections()).extracting(UnusedIndexes.Connection::connection,
                UnusedIndexes.Connection::verdict)
            .contains(org.assertj.core.api.Assertions.tuple("replica", Coverage.Verdict.UNKNOWN));
        assertThat(report.basis()).contains("could not be read");
    }

    /**
     * A server up an hour has not been counting long enough for silence to mean
     * disuse. Reported as IMPAIRED rather than withheld: the reader should see
     * that it answered, and that its answer cannot carry a deletion.
     */
    @Test
    void aWindowTooShortToSupportTheClaimIsSaidSo() {
        UnusedIndexes.Report report = serviceOver(Map.of(
            "primary", new Answers(List.of(index("orders", "idx_old")), List.of(), UP_AN_HOUR)
        )).report("prod");

        assertThat(report.connections()).singleElement()
            .extracting(UnusedIndexes.Connection::verdict).isEqualTo(Coverage.Verdict.IMPAIRED);
    }

    /**
     * The uptime is an UPPER BOUND, and the report has to say so: Performance
     * Schema can be truncated without a restart, and a truncate zeroes every
     * index at once — so just after one, everything reads as unused.
     */
    @Test
    void theBasisNamesUptimeAsAnUpperBoundAndWhyThatMatters() {
        UnusedIndexes.Report report = serviceOver(Map.of(
            "primary", new Answers(List.of(), List.of(), UP_LONG_ENOUGH)
        )).report("prod");

        assertThat(report.basis()).contains("UPPER BOUND").contains("truncated");
    }

    /** Reading is what connects, so the page shows the last report and reads only when asked (sqlx-1bo.2). */
    @Test
    void keepsTheLastReportPerEnvironmentWithWhenItWasRead() {
        UnusedIndexService service = serviceOver(Map.of(
            "primary", new Answers(List.of(index("orders", "idx_old")), List.of(), UP_LONG_ENOUGH)));
        assertThat(service.latest("prod")).isEmpty();

        UnusedIndexes.Report read = service.report("prod");

        assertThat(read.readAt()).isNotNull();
        assertThat(service.latest("prod")).contains(read);
    }

    /** A report read before a restart is what the page shows after it, with when it was read and how long it took. */
    @Test
    void theLastReportOutlivesARestart(@TempDir Path directory) {
        UnusedIndexReportStore store = new UnusedIndexReportStore(directory, JsonMapper.builder().findAndAddModules().build());
        EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
        DatabaseServer primary = new DatabaseServer("primary",
            new ConnectionInfo("jdbc:mysql://nowhere", "u", "p"), List.of(), mock(SchemaRepositoryFactory.class));
        when(registry.getOrDefault(anyString())).thenReturn(new Environment("prod", List.of(primary)));
        UnusedIndexService before = new UnusedIndexService(registry, store, 7) {
            @Override
            Reading read(DatabaseServer server) {
                return new Reading(List.of(index("orders", "idx_old")), List.of(), UP_LONG_ENOUGH);
            }
        };
        UnusedIndexes.Report read = before.report("prod");
        assertThat(read.readMillis()).isNotNegative();
        assertThat(read.minimumDays()).isEqualTo(7);

        UnusedIndexService after = new UnusedIndexService(registry, store, 7) {
            @Override
            Reading read(DatabaseServer server) {
                throw new AssertionError("showing the kept report must not connect");
            }
        };
        assertThat(after.latest("prod")).contains(read);
    }

    /**
     * A server with no sys schema is still read, from performance_schema, and
     * says so (sqlx-jip). Only a grammar error falls back: that is how a
     * missing view answers, and anything else is a real failure to read.
     */
    @Test
    void aServerWithoutTheSysViewIsReadFromPerformanceSchemaInstead() {
        List<UnusedIndexes.Index> found = List.of(index("orders", "idx_old"));
        UnusedIndexService.Unused unused = UnusedIndexService.unused(sql -> {
            if (sql.contains("sys.schema_unused_indexes")) {
                throw new BadSqlGrammarException("unused", sql, new SQLException("Table 'sys.schema_unused_indexes' doesn't exist"));
            }
            return found;
        });
        assertThat(unused.indexes()).isEqualTo(found);
        assertThat(unused.note()).contains("performance_schema");

        UnusedIndexService.Unused usual = UnusedIndexService.unused(sql -> found);
        assertThat(usual.note()).isNull();

        assertThatThrownBy(() -> UnusedIndexService.unused(sql -> {
            throw new DataAccessResourceFailureException("connection lost");
        })).isInstanceOf(DataAccessResourceFailureException.class);
    }

    /** The fallback is the sys view's own definition, filters and all, not an approximation of it. */
    @Test
    void theFallbackKeepsTheViewsFilters() {
        assertThat(UnusedIndexService.UNUSED_WITHOUT_SYS)
            .contains("count_star = 0").contains("<> 'PRIMARY'").contains("non_unique = 1")
            .contains("seq_in_index = 1").contains("'performance_schema'");
    }
}
