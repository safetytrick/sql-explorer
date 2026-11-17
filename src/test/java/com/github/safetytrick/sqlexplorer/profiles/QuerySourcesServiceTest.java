package com.github.safetytrick.sqlexplorer.profiles;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.environment.Cluster;
import com.github.safetytrick.sqlexplorer.environment.ConnectionInfo;
import com.github.safetytrick.sqlexplorer.environment.DatabaseServer;
import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import com.github.safetytrick.sqlexplorer.environment.ServerRole;
import com.github.safetytrick.sqlexplorer.evidence.CaptureRehydrator;
import com.github.safetytrick.sqlexplorer.evidence.SqlEvidenceInputService;
import com.github.safetytrick.sqlexplorer.evidence.SqlImportStats;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepository;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryManager;
import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlInput;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A statement's figures on each source it ran on, from the same per-source
 * corpus search and topology read (sqlx-rw2.9).
 */
class QuerySourcesServiceTest {

    private static final LocalDateTime START = LocalDateTime.parse("2026-11-01T00:00:00");
    private static final Instant CAPTURED = Instant.parse("2026-11-11T12:00:00Z");

    /** executions and seconds of the one statement, per source; "export" names no configured server. */
    private static final Map<String, long[]> FIGURES = Map.of(
        "shard1", new long[] {100, 10},
        "shard1-replica", new long[] {300, 30},
        "export", new long[] {50, 0});

    private QuerySourcesService service;

    private static SqlProfile profile(String sourceRef) {
        long[] figures = FIGURES.get(sourceRef);
        BigDecimal picos = BigDecimal.valueOf(figures[1]).multiply(BigDecimal.valueOf(1_000_000_000_000L));
        RuntimeMetrics metrics = new RuntimeMetrics(false, figures[0], 0L, 0L, picos, BigDecimal.ZERO,
            figures[0] == 0 ? BigDecimal.ZERO : picos.divide(BigDecimal.valueOf(figures[0])),
            7L, 0L, 70L, 0L, 0L, 0L, 0L, 0L, "d1", START.plusDays(5), START, BigDecimal.ZERO, 2L, 0L, List.of());
        SqlProfile profile = new SqlProfile();
        profile.setId(sourceRef + "-1");
        profile.setQuery("update account set status = ?");
        profile.setType("UPDATE");
        profile.setDigest("d1-" + sourceRef);
        profile.setNormalizedDigest("d1");
        profile.setSchemaName("app_main");
        profile.setQueryActivityStatus(QueryActivityStatus.UNKNOWN);
        profile.setRuntimeMetrics(metrics);
        profile.setTables(List.of());
        profile.setSources(List.of(sourceRef));
        return profile;
    }

    private static SqlSource source(String ref) {
        return new SqlSource() {
            @Override public Stream<SqlInput> getSql() { return Stream.empty(); }
            @Override public Instant getCreatedOn() { return Instant.EPOCH; }
            @Override public String getSourceRef() { return ref; }
        };
    }

    private static SqlRepository repositoryFor(SqlSource source) {
        SqlProfile profile = profile(source.getSourceRef());
        return new SqlRepository() {
            @Override public SqlProfile getByDigest(String id) {
                return id.equals(profile.getNormalizedDigest()) || id.equals(profile.getDigest()) ? profile : null;
            }
            @Override public Stream<SqlProfile> getTableQueries(TableReference t) { return Stream.empty(); }
            @Override public Stream<SqlProfile> getQueriesForColumn(TableReference t, String c) { return Stream.empty(); }
            @Override public Stream<TableReference> getAllTableReferences() { return Stream.empty(); }
            @Override public Stream<SqlProfile> getAllAnalyzedSql() { return Stream.of(profile); }
            @Override public SqlSource getSource() { return source; }
            @Override public SqlImportStats getImportStats() { return SqlImportStats.builder().build(); }
            @Override public CaptureContext getCaptureContext() {
                return new CaptureContext(CaptureContext.Origin.PERFORMANCE_SCHEMA, source.getSourceRef(), CAPTURED,
                    1, START, START.plusDays(5), Map.of());
            }
        };
    }

    private static DatabaseServer server(String name, ServerRole... roles) {
        return new DatabaseServer(name, new ConnectionInfo("jdbc:mysql://db.example.com:3306", "reader", null),
            List.of(), mock(SchemaRepositoryFactory.class), null, Set.of(roles));
    }

    @BeforeEach
    void setUp() {
        DatabaseServer primary = server("shard1", ServerRole.PRIMARY);
        DatabaseServer replica = server("shard1-replica", ServerRole.REPLICA, ServerRole.SAMPLING);
        Environment environment = Environment.ofClusters("env",
            List.of(new Cluster("shard1", List.of(primary, replica), List.of(primary))), false, null);

        SqlEvidenceInputService input = mock(SqlEvidenceInputService.class);
        when(input.fromPerformanceSchema(any(), any(), any(), any()))
            .thenAnswer(call -> repositoryFor(call.getArgument(0)));
        EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
        // the manager's view of the environment is not under test; the service reads the real one
        when(registry.get(anyString())).thenReturn(mock(Environment.class));
        SqlRepositoryManager manager =
            new SqlRepositoryManager(input, registry, mock(CaptureRehydrator.class), 4, Clock.systemUTC());
        manager.loadFromSqlSources("env", Stream.of(source("shard1"), source("shard1-replica"), source("export")));

        EnvironmentContext context = mock(EnvironmentContext.class);
        when(context.current()).thenReturn(environment);
        service = new QuerySourcesService(manager, context);
    }

    @Test
    void eachSourceCarriesItsOwnFiguresTheMostTimeFirst() {
        List<QuerySourcesService.QuerySource> sources = service.whereItRuns("d1");

        assertThat(sources).extracting(QuerySourcesService.QuerySource::source)
            .containsExactly("shard1-replica", "shard1", "export");
        QuerySourcesService.QuerySource replica = sources.getFirst();
        assertThat(replica.executions()).isEqualTo(300);
        assertThat(replica.seconds()).isCloseTo(30, within(1e-9));
        assertThat(replica.averageMillis()).isCloseTo(100, within(1e-9));
        assertThat(replica.shareOfTime()).isCloseTo(0.75, within(1e-9));
        assertThat(replica.cluster()).isEqualTo("shard1");
        assertThat(replica.roles()).containsExactlyInAnyOrder("replica", "sampling");
        assertThat(replica.capturedAt()).isEqualTo(CAPTURED);
    }

    /** A source named after no configured server is still where it ran; it just has no cluster or roles. */
    @Test
    void aSourceNamingNoConfiguredServerHasNoClusterOrRoles() {
        QuerySourcesService.QuerySource export = service.whereItRuns("d1").getLast();

        assertThat(export.source()).isEqualTo("export");
        assertThat(export.cluster()).isNull();
        assertThat(export.roles()).isEmpty();
        assertThat(export.shareOfTime()).isZero();
    }

    @Test
    void aPerformanceSchemaDigestFindsTheSameStatement() {
        assertThat(service.whereItRuns("d1-shard1")).extracting(QuerySourcesService.QuerySource::source)
            .containsExactly("shard1");
    }

    @Test
    void anUnknownStatementIsNull() {
        assertThat(service.whereItRuns("nope")).isNull();
    }
}
