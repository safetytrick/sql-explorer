package com.github.safetytrick.sqlexplorer.topology;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
import com.github.safetytrick.sqlexplorer.topology.ServerComparison.By;
import com.github.safetytrick.sqlexplorer.topology.ServerComparison.Flag;
import com.github.safetytrick.sqlexplorer.topology.ServerComparison.Row;
import com.github.safetytrick.sqlexplorer.topology.ServerComparison.Sort;
import com.github.safetytrick.sqlexplorer.topology.ServerComparisonService.Options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServerComparisonServiceTest {

    private static final LocalDateTime START = LocalDateTime.parse("2026-11-01T00:00:00");
    private static final int DAYS = 10;

    private static DatabaseServer server(String name, ServerRole... roles) {
        SourceDefinition source = new SourceDefinition();
        source.setType("x-statement-analysis");
        return new DatabaseServer(name, new ConnectionInfo("jdbc:mysql://db.example.com:3306", "reader", null),
            List.of(source), mock(SchemaRepositoryFactory.class), null, Set.of(roles));
    }

    private static Environment environment() {
        DatabaseServer primary = server("shard1", ServerRole.PRIMARY);
        DatabaseServer replicaA = server("shard1-replica-a", ServerRole.REPLICA);
        DatabaseServer replicaB = server("shard1-replica-b", ServerRole.REPLICA);
        DatabaseServer etl = server("shard1-etl", ServerRole.ANALYTICS);
        DatabaseServer primary2 = server("shard2", ServerRole.PRIMARY);
        return Environment.ofClusters("env", List.of(
            new Cluster("shard1", List.of(etl, replicaB, replicaA, primary), List.of(primary)),
            new Cluster("shard2", List.of(primary2), List.of(primary2))), false, null);
    }

    private static ServerComparisonService service(SqlRepository... repositories) {
        SqlRepositoryManager manager = mock(SqlRepositoryManager.class);
        when(manager.getLoadState("env")).thenReturn(
            new SqlRepositoryManager.LoadState(repositories.length, Instant.parse("2026-11-10T00:00:00Z"), null,
                true));
        when(manager.getRepositories("env")).thenReturn(List.of(repositories));
        when(manager.getSqlRepository(eq("env"), any(SourceScope.class))).thenAnswer(call -> {
            SourceScope scope = call.getArgument(1);
            return repository("scoped", Stream.of(repositories)
                .filter(repository -> scope.includes(repository.sourceRef()))
                .flatMap(SqlRepository::getAllAnalyzedSql)
                .toArray(SqlProfile[]::new));
        });
        EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
        when(registry.get("env")).thenReturn(environment());
        return new ServerComparisonService(manager, registry);
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

    private static SqlProfile profile(String digest, String type, String table, long executions, double seconds,
                                      long rowsScanned, long rowsAffected) {
        RuntimeMetrics metrics = new RuntimeMetrics(false, executions, 0L, 0L,
            BigDecimal.valueOf(seconds).multiply(BigDecimal.valueOf(1_000_000_000_000L)), BigDecimal.ZERO,
            BigDecimal.ZERO, 0L, 0L, rowsScanned, 0L, 0L, 0L, 0L, 0L,
            digest, START.plusDays(DAYS), START, BigDecimal.ZERO, rowsAffected, 0L, List.of());
        SqlProfile profile = new SqlProfile();
        profile.setId(digest);
        profile.setQuery(type.toLowerCase() + " from " + table);
        profile.setType(type);
        profile.setDigest(digest);
        profile.setNormalizedDigest(digest);
        profile.setSchemaName("app_main");
        profile.setQueryActivityStatus(QueryActivityStatus.UNKNOWN);
        profile.setRuntimeMetrics(metrics);
        profile.setTables(List.of(new TableReference("app_main", table, table, "app_main")));
        return profile;
    }

    private static SqlProfile select(String digest, String table, long executions, double seconds, long rows) {
        return profile(digest, "SELECT", table, executions, seconds, rows, 0);
    }

    private static ServerComparisonService everyFlag() {
        return service(
            repository("shard1",
                select("reads", "account", 100, 10, 100),
                profile("write", "UPDATE", "site", 500, 5, 500, 500),
                profile("wr", "UPDATE", "site", 100, 1, 100, 100)),
            repository("shard1-replica-a",
                select("reads", "account", 1000, 100, 1000),
                select("skew", "account", 900, 90, 900),
                select("plan", "account", 100, 1, 1_000),
                select("slow", "site", 100, 1, 100),
                profile("wr", "UPDATE", "site", 100, 1, 100, 100)),
            repository("shard1-replica-b",
                select("reads", "account", 1000, 100, 1000),
                select("skew", "account", 100, 10, 100),
                select("plan", "account", 100, 50, 500_000),
                select("slow", "site", 100, 10, 100)),
            repository("shard1-etl", select("pinned", "account_site", 20, 40, 20_000)),
            repository("shard2", select("elsewhere", "account", 10, 1, 10)));
    }

    private static List<SqlProfile> matched() {
        return List.of(
            select("reads", "account", 2100, 210, 2100),
            profile("write", "UPDATE", "site", 500, 5, 500, 500),
            profile("wr", "UPDATE", "site", 200, 2, 200, 200),
            select("skew", "account", 1000, 100, 1000),
            select("plan", "account", 200, 51, 501_000),
            select("slow", "site", 200, 11, 200),
            select("pinned", "account_site", 20, 40, 20_000),
            select("elsewhere", "account", 10, 1, 10));
    }

    private static Options options() {
        return new Options(null, null, Set.of(), false, null, null);
    }

    private static Row row(ServerComparison.Report report, String key) {
        return report.rows().stream().filter(r -> r.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void eachMatchedQueryIsLinedUpAgainstTheBusiestClustersServersPrimaryFirst() {
        ServerComparison.Report report = everyFlag().compare("env", matched(), options());

        assertThat(report.cluster()).isEqualTo("shard1");
        assertThat(report.clusters()).containsExactly("shard1", "shard2");
        assertThat(report.servers()).extracting(ServerComparison.Column::name)
            .containsExactly("shard1", "shard1-replica-b", "shard1-replica-a", "shard1-etl");
        assertThat(report.servers().get(0).windowDays()).isCloseTo(DAYS, within(1e-9));
        assertThat(report.matched()).isEqualTo(8);
        assertThat(report.compared()).isEqualTo(7);

        Row reads = row(report, "reads");
        assertThat(reads.cells().get(0).executions()).isEqualTo(100);
        assertThat(reads.cells().get(0).secondsPerDay()).isCloseTo(1, within(1e-9));
        assertThat(reads.cells().get(3)).isNull();
        assertThat(reads.secondsPerDay()).isCloseTo(21, within(1e-9));
    }

    @Test
    void eachDifferenceIsFlaggedWithTheTimePerDayItIsAbout() {
        ServerComparison.Report report = everyFlag().compare("env", matched(), options());

        assertThat(row(report, "reads").flags()).containsExactly(Flag.ON_PRIMARY);
        assertThat(row(report, "reads").difference()).isCloseTo(1, within(1e-9));

        assertThat(row(report, "skew").flags()).containsExactly(Flag.SKEWED);
        assertThat(row(report, "skew").difference()).isCloseTo(4, within(1e-9));

        assertThat(row(report, "plan").flags()).containsExactly(Flag.PLAN_DIFFERS);
        assertThat(row(report, "plan").difference()).isCloseTo(5 * (1 - 10d / 5000), within(1e-9));

        assertThat(row(report, "slow").flags()).containsExactly(Flag.LATENCY_DIFFERS);
        assertThat(row(report, "slow").difference()).isCloseTo(1 * (1 - 10d / 100), within(1e-9));

        assertThat(row(report, "pinned").flags()).containsExactly(Flag.SINGLE_SERVER);
        assertThat(row(report, "wr").flags()).containsExactly(Flag.REPLICA_WRITES);

        assertThat(report.rows()).extracting(Row::key).doesNotContain("write");
        assertThat(report.unflagged()).isEqualTo(1);
        assertThat(report.total()).isEqualTo(6);
        assertThat(report.flagCounts()).containsEntry(Flag.ON_PRIMARY, 1).containsEntry(Flag.SKEWED, 1);

        assertThat(report.rows()).extracting(Row::key).startsWith("plan", "skew", "pinned");
    }

    @Test
    void theFlagFilterAllAndSortNarrowAndOrderTheRows() {
        ServerComparisonService service = everyFlag();

        ServerComparison.Report onlyPlans = service.compare("env", matched(),
            new Options(null, null, Set.of(Flag.PLAN_DIFFERS, Flag.SKEWED), false, null, null));
        assertThat(onlyPlans.rows()).extracting(Row::key).containsExactly("plan", "skew");
        assertThat(onlyPlans.flagCounts()).containsEntry(Flag.ON_PRIMARY, 1);

        ServerComparison.Report everything = service.compare("env", matched(),
            new Options(null, null, Set.of(), true, Sort.TIME, 2));
        assertThat(everything.total()).isEqualTo(7);
        assertThat(everything.rows()).extracting(Row::key).containsExactly("reads", "skew");
    }

    @Test
    void byTableAddsItsQueriesFiguresAndPoolsTheirFlags() {
        ServerComparison.Report report = everyFlag().compare("env", matched(),
            new Options(null, By.TABLE, Set.of(), true, null, null));

        Row account = row(report, "app_main.account");
        assertThat(account.queries()).isEqualTo(3);
        assertThat(account.kind()).isNull();
        assertThat(account.flags()).containsExactly(Flag.ON_PRIMARY, Flag.SKEWED, Flag.PLAN_DIFFERS);
        assertThat(account.cells().get(2).secondsPerDay()).isCloseTo(19.1, within(1e-9));
        assertThat(account.cells().get(2).executions()).isEqualTo(2000);
        assertThat(row(report, "app_main.site").flags()).containsExactly(Flag.LATENCY_DIFFERS, Flag.REPLICA_WRITES);
    }

    @Test
    void anotherClusterCanBeAskedForByName() {
        ServerComparison.Report report = everyFlag().compare("env", matched(),
            new Options("shard2", null, Set.of(), true, null, null));

        assertThat(report.cluster()).isEqualTo("shard2");
        assertThat(report.rows()).extracting(Row::key).containsExactly("elsewhere");
        assertThat(report.rows().get(0).flags()).isEmpty();
        assertThat(report.flagCounts()).isEqualTo(Map.of());
    }
}
