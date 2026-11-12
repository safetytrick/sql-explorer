package com.github.safetytrick.sqlexplorer.topology;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.environment.Cluster;
import com.github.safetytrick.sqlexplorer.environment.ConnectionInfo;
import com.github.safetytrick.sqlexplorer.environment.DatabaseServer;
import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import com.github.safetytrick.sqlexplorer.environment.ServerRole;
import com.github.safetytrick.sqlexplorer.environment.SourceDefinition;
import com.github.safetytrick.sqlexplorer.evidence.SourceScope;
import com.github.safetytrick.sqlexplorer.evidence.SqlImportStats;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepository;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryManager;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlInput;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import com.github.safetytrick.sqlexplorer.model.QueryKind;
import com.github.safetytrick.sqlexplorer.topology.Topology.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TopologyServiceTest {

    private static final LocalDateTime START = LocalDateTime.parse("2026-11-01T00:00:00");

    private static SourceDefinition statementSource() {
        SourceDefinition source = new SourceDefinition();
        source.setType("x-statement-analysis");
        return source;
    }

    private static DatabaseServer server(String name, boolean captured, ServerRole... roles) {
        return new DatabaseServer(name, new ConnectionInfo("jdbc:mysql://db.example.com:3306", "reader", null),
            captured ? List.of(statementSource()) : List.of(), mock(SchemaRepositoryFactory.class), null,
            Set.of(roles));
    }

    /** shard1: primary, replica, an uncaptured analytics box; shard2: a primary that failed to read. */
    private static Environment environment() {
        DatabaseServer primary = server("shard1", true, ServerRole.PRIMARY);
        DatabaseServer replica = server("shard1-replica", true, ServerRole.REPLICA);
        DatabaseServer etl = server("shard1-etl", false, ServerRole.ANALYTICS);
        DatabaseServer primary2 = server("shard2", true, ServerRole.PRIMARY);
        // declared replica-first, to show the primary is put first
        return Environment.ofClusters("env", List.of(
            new Cluster("shard1", List.of(etl, replica, primary), List.of(primary)),
            new Cluster("shard2", List.of(primary2), List.of(primary2))), false, null);
    }

    private static TopologyService service(List<SqlRepositoryManager.SourceFailure> failures,
                                           SqlRepository... repositories) {
        SqlRepositoryManager manager = mock(SqlRepositoryManager.class);
        when(manager.getLoadState("env")).thenReturn(
            new SqlRepositoryManager.LoadState(repositories.length, Instant.parse("2026-11-10T00:00:00Z"), null,
                true));
        when(manager.getRepositories("env")).thenReturn(List.of(repositories));
        when(manager.getFailedSources("env")).thenReturn(failures);
        // the scoped corpus: what a search with sources=<ref> reads
        when(manager.getSqlRepository(eq("env"), any(SourceScope.class))).thenAnswer(call -> {
            SourceScope scope = call.getArgument(1);
            return repository("scoped", Stream.of(repositories)
                .filter(repository -> scope.includes(repository.sourceRef()))
                .flatMap(SqlRepository::getAllAnalyzedSql)
                .toArray(SqlProfile[]::new));
        });
        EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
        when(registry.get("env")).thenReturn(environment());
        return new TopologyService(manager, registry);
    }

    private static SqlRepository repository(String sourceRef, SqlProfile... profiles) {
        return new SqlRepository() {
            @Override public SqlProfile getByDigest(String id) { return null; }
            @Override public Stream<SqlProfile> getTableQueries(TableReference table) { return Stream.empty(); }
            @Override public Stream<SqlProfile> getQueriesForColumn(TableReference t, String c) {
                return Stream.empty();
            }
            @Override public Stream<TableReference> getAllTableReferences() { return Stream.empty(); }
            @Override public Stream<SqlProfile> getAllAnalyzedSql() { return Stream.of(profiles); }
            @Override public SqlSource getSource() {
                return new SqlSource() {
                    @Override public Stream<SqlInput> getSql() { return Stream.empty(); }
                    @Override public Instant getCreatedOn() { return Instant.parse("2026-11-10T00:00:00Z"); }
                    @Override public String getSourceRef() { return sourceRef; }
                };
            }
            @Override public SqlImportStats getImportStats() { return null; }
        };
    }

    /** {@code seconds} of total time over {@code days} days from START. */
    private static SqlProfile profile(String digest, String type, long executions, double seconds, int days) {
        RuntimeMetrics metrics = new RuntimeMetrics(false, executions, 0L, 0L,
            BigDecimal.valueOf(seconds).multiply(BigDecimal.valueOf(1_000_000_000_000L)), BigDecimal.ZERO,
            BigDecimal.ZERO, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
            digest, START.plusDays(days), START, BigDecimal.ZERO, 0L, 0L, List.of());
        SqlProfile profile = new SqlProfile();
        profile.setId(digest);
        profile.setQuery(type.toLowerCase() + " from account");
        profile.setType(type);
        profile.setDigest(digest);
        profile.setNormalizedDigest(digest);
        profile.setSchemaName("app_main");
        profile.setQueryActivityStatus(QueryActivityStatus.UNKNOWN);
        profile.setRuntimeMetrics(metrics);
        profile.setTables(List.of());
        return profile;
    }

    @Test
    void eachServerCountsItsOwnStatementsSplitIntoReadsWritesAndOther() {
        TopologyService service = service(List.of(),
            repository("shard1",
                profile("a", "SELECT", 600, 60, 10),
                profile("b", "UPDATE", 300, 30, 10),
                profile("c", "SET", 100, 10, 10)),
            repository("shard1-replica", profile("a", "select", 1000, 100, 4)),
            repository("shard2", profile("d", "INSERT", 50, 0, 10)));

        Topology.Report report = service.report("env");

        assertThat(report.loaded()).isTrue();
        Topology.Cluster shard1 = report.clusters().get(0);
        assertThat(shard1.servers()).extracting(Topology.Server::name)
            .containsExactly("shard1", "shard1-replica", "shard1-etl");

        Topology.Server primary = shard1.servers().get(0);
        assertThat(primary.status()).isEqualTo(Status.READ);
        assertThat(primary.schemaSource()).isTrue();
        assertThat(primary.roles()).containsExactly("primary");
        assertThat(primary.load().executions()).isEqualTo(1000);
        assertThat(primary.load().seconds()).isCloseTo(100, within(1e-9));
        assertThat(primary.load().shares()).extracting(Topology.Share::kind, Topology.Share::executions)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(QueryKind.READ, 600L),
                org.assertj.core.groups.Tuple.tuple(QueryKind.WRITE, 300L),
                org.assertj.core.groups.Tuple.tuple(QueryKind.OTHER, 100L),
                org.assertj.core.groups.Tuple.tuple(QueryKind.UNKNOWN, 0L));
        // 100 s over its own 10-day window
        assertThat(primary.load().secondsPerDay()).isCloseTo(10, within(1e-9));
        assertThat(primary.window().days()).isCloseTo(10, within(1e-9));
        assertThat(primary.shareOfTime()).isCloseTo(0.5, within(1e-9));

        // the same digest counts again where it ran; lowercase type still reads
        Topology.Server replica = shard1.servers().get(1);
        assertThat(replica.load().shares().get(0).executions()).isEqualTo(1000);
        assertThat(replica.load().secondsPerDay()).isCloseTo(25, within(1e-9));

        Topology.Server etl = shard1.servers().get(2);
        assertThat(etl.status()).isEqualTo(Status.NO_SOURCE);
        assertThat(etl.load()).isNull();

        // a cluster's rate is its servers' rates added, not its total over a merged window
        assertThat(shard1.load().seconds()).isCloseTo(200, within(1e-9));
        assertThat(shard1.load().secondsPerDay()).isCloseTo(35, within(1e-9));
        assertThat(report.total().executions()).isEqualTo(2050);
        assertThat(report.others()).isEmpty();
    }

    @Test
    void aServerTheLoadCouldNotReadSaysSoWhetherOrNotItKeptItsPreviousCapture() {
        TopologyService service = service(
            List.of(new SqlRepositoryManager.SourceFailure("shard2", "Access denied"),
                new SqlRepositoryManager.SourceFailure("shard1-replica", "UnknownHostException")),
            repository("shard1", profile("a", "SELECT", 1, 1, 1)),
            repository("shard2", profile("d", "INSERT", 1, 1, 1)));

        Topology.Report report = service.report("env");

        Topology.Server stale = report.clusters().get(1).servers().get(0);
        assertThat(stale.status()).isEqualTo(Status.STALE);
        assertThat(stale.reason()).contains("previous capture").contains("Access denied");
        Topology.Server unreachable = report.clusters().get(0).servers().get(1);
        assertThat(unreachable.status()).isEqualTo(Status.UNREACHABLE);
        assertThat(unreachable.reason()).isEqualTo("UnknownHostException");
    }

    @Test
    void aSourceNamingNoConfiguredServerIsCountedApart() {
        TopologyService service = service(List.of(),
            repository("shard1", profile("a", "SELECT", 1, 1, 1)),
            repository("export-2026-10", profile("b", "DELETE", 3, 2, 1)));

        Topology.Report report = service.report("env");

        assertThat(report.others()).extracting(Topology.Server::name).containsExactly("export-2026-10");
        assertThat(report.total().seconds()).isCloseTo(3, within(1e-9));
    }

    @Test
    void nothingLoadedIsSaidRatherThanShownAsZero() {
        SqlRepositoryManager manager = mock(SqlRepositoryManager.class);
        EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
        when(registry.get("env")).thenReturn(environment());

        Topology.Report report = new TopologyService(manager, registry).report("env");

        assertThat(report.loaded()).isFalse();
        assertThat(report.clusters().get(0).servers()).allSatisfy(s -> {
            assertThat(s.status()).isEqualTo(Status.NOT_LOADED);
            assertThat(s.load()).isNull();
        });
    }
}
