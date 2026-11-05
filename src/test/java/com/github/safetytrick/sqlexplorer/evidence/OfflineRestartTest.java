package com.github.safetytrick.sqlexplorer.evidence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.analysis.AnalysisFailureRecorder;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.environment.ConnectionInfo;
import com.github.safetytrick.sqlexplorer.environment.DatabaseServer;
import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import com.github.safetytrick.sqlexplorer.environment.SourceDefinition;
import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlInput;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSourceFactory;
import com.github.safetytrick.sqlexplorer.schema.LoadedSchema;
import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import com.github.safetytrick.sqlexplorer.schema.SchemaSnapshot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A restart with a fresh cache serves it and connects to nothing; a stale or
 * missing one is pulled, and an unreachable server leaves the environment up
 * and saying what is wrong (sqlx-nbi).
 */
class OfflineRestartTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** Every live read counts here and fails, as an unreachable server does. */
    private final AtomicInteger liveReads = new AtomicInteger();
    private final CaptureRehydrator rehydrator = mock(CaptureRehydrator.class);
    private final SchemaRepositoryFactory schemas = mock(SchemaRepositoryFactory.class);
    private final SqlEvidenceInputService input = mock(SqlEvidenceInputService.class);

    private SqlSource live(String ref) {
        return new SqlSource() {
            @Override public Stream<SqlInput> getSql() {
                liveReads.incrementAndGet();
                throw new IllegalStateException("Unable to load sql from: " + ref,
                    new java.sql.SQLException("Communications link failure"));
            }
            @Override public Instant getCreatedOn() { return NOW; }
            @Override public String getSourceRef() { return ref; }
        };
    }

    private static SqlRepository captured(String ref, Instant at) {
        CaptureContext context = new CaptureContext(CaptureContext.Origin.PERFORMANCE_SCHEMA, ref, at, 1, null, null,
            java.util.Map.of());
        return new SqlRepository() {
            @Override public SqlProfile getByDigest(String id) { return null; }
            @Override public Stream<SqlProfile> getTableQueries(TableReference t) { return Stream.empty(); }
            @Override public Stream<SqlProfile> getQueriesForColumn(TableReference t, String c) { return Stream.empty(); }
            @Override public Stream<TableReference> getAllTableReferences() { return Stream.empty(); }
            @Override public Stream<SqlProfile> getAllAnalyzedSql() { return Stream.empty(); }
            @Override public SqlSource getSource() { return new CapturedSqlSource(java.nio.file.Path.of(ref), context); }
            @Override public SqlImportStats getImportStats() { return SqlImportStats.builder().build(); }
            @Override public CaptureContext getCaptureContext() { return context; }
        };
    }

    private void captureOf(String ref, Duration age) {
        when(rehydrator.rehydrate(ref)).thenReturn(captured(ref, NOW.minus(age)));
    }

    private void snapshotOf(String environment, String cluster) {
        when(schemas.lastKnown(new SchemaSnapshot.Key(environment, cluster))).thenReturn(Optional.of(
            new SchemaRepositoryFactory.SchemaLoad(mock(LoadedSchema.class), null, true, false, NOW, cluster)));
    }

    private DatabaseServer server(String environment, String name) {
        SourceDefinition definition = new SourceDefinition();
        definition.setType("fake");
        return new DatabaseServer(name, new ConnectionInfo("jdbc:mysql://db.example.com:3306/", "reader", "pw"),
            List.of(definition), schemas, new SchemaSnapshot.Key(environment, name));
    }

    private record Started(EnvironmentLoader loader, SqlRepositoryManager manager, Environment environment) { }

    private Started start(boolean offline) {
        Environment environment = new Environment("offline-dummy",
            List.of(server("offline-dummy", "primary"), server("offline-dummy", "replica")));
        EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
        when(registry.list()).thenReturn(List.of(environment));
        when(registry.getDefaultName()).thenReturn("offline-dummy");
        when(registry.get("offline-dummy")).thenReturn(environment);
        SqlRepositoryManager manager = new SqlRepositoryManager(input, registry, rehydrator, 4, CLOCK);
        SqlSourceFactory factory = new SqlSourceFactory() {
            @Override public String type() { return "fake"; }
            @Override public Stream<? extends SqlSource> create(String env, DatabaseServer server,
                                                                SourceDefinition definition) {
                return Stream.of(live(server.getName()));
            }
        };
        EnvironmentLoader loader = new EnvironmentLoader(List.of(factory), manager, registry,
            mock(AnalysisFailureRecorder.class), CLOCK, Duration.ofHours(24), offline);
        loader.serveCapturesAtStartup();
        loader.loadConfiguredSourcesAtStartup();
        return new Started(loader, manager, environment);
    }

    @Test
    void aFreshCacheIsServedAndNothingIsReadLive() {
        captureOf("primary", Duration.ofHours(2));
        captureOf("replica", Duration.ofHours(20));
        snapshotOf("offline-dummy", "primary");
        snapshotOf("offline-dummy", "replica");

        Started started = start(false);

        assertThat(liveReads).hasValue(0);
        assertThat(started.environment().isCached()).isTrue();
        assertThat(started.environment().getServers()).allMatch(DatabaseServer::isOffline);
        EnvironmentHealth health = started.loader().health("offline-dummy");
        assertThat(health.state()).isEqualTo(EnvironmentHealth.State.CACHED);
        assertThat(health.oldestCapture()).isEqualTo(NOW.minus(Duration.ofHours(20)));
        assertThat(health.problems()).isEmpty();
    }

    /** One stale source: the whole environment is pulled, and an unreachable server leaves it up, stale. */
    @Test
    void oneStaleSourcePullsTheWholeEnvironment() {
        captureOf("primary", Duration.ofHours(2));
        captureOf("replica", Duration.ofHours(30));
        snapshotOf("offline-dummy", "primary");
        snapshotOf("offline-dummy", "replica");

        Started started = start(false);

        assertThat(liveReads).hasValue(2);
        assertThat(started.environment().isCached()).isFalse();
        assertThat(started.manager().servedCaptures("offline-dummy")).containsOnlyKeys("primary", "replica");
        EnvironmentHealth health = started.loader().health("offline-dummy");
        assertThat(health.state()).isEqualTo(EnvironmentHealth.State.STALE);
        assertThat(health.lastPull().succeeded()).isFalse();
        assertThat(health.problems()).anySatisfy(problem -> assertThat(problem).contains("over the 1d 0h limit"))
            .anySatisfy(problem -> assertThat(problem).contains("Communications link failure"));
    }

    /** A source never captured, and unreachable: startup completes and the environment says it is partial. */
    @Test
    void aMissingCaptureAndAnUnreachableServerLeaveItPartialNotDown() {
        captureOf("primary", Duration.ofHours(2));
        snapshotOf("offline-dummy", "primary");
        snapshotOf("offline-dummy", "replica");

        Started started = start(false);

        assertThat(liveReads).hasValue(2);
        EnvironmentHealth health = started.loader().health("offline-dummy");
        assertThat(health.state()).isEqualTo(EnvironmentHealth.State.PARTIAL);
        assertThat(health.problems()).anySatisfy(problem -> assertThat(problem).startsWith("replica: nothing to serve"));
    }

    /** Nothing captured and nothing reachable: up, and unavailable rather than empty-and-fine. */
    @Test
    void noCaptureAtAllAndNothingReachableIsUnavailable() {
        Started started = start(false);

        assertThat(started.loader().health("offline-dummy").state()).isEqualTo(EnvironmentHealth.State.UNAVAILABLE);
    }

    /** Offline by configuration: served from whatever there is, however old, and nothing is read live. */
    @Test
    void offlineByConfigurationNeverReadsLive() {
        captureOf("primary", Duration.ofDays(9));
        captureOf("replica", Duration.ofDays(9));

        Started started = start(true);

        assertThat(liveReads).hasValue(0);
        assertThat(started.environment().isCached()).isTrue();
        assertThat(started.loader().health("offline-dummy").state()).isEqualTo(EnvironmentHealth.State.STALE);
    }

    /** A pull is a request to go live: the refusal lifts and the servers are read. */
    @Test
    void aPullTakesACachedEnvironmentLive() {
        captureOf("primary", Duration.ofHours(2));
        captureOf("replica", Duration.ofHours(2));
        snapshotOf("offline-dummy", "primary");
        snapshotOf("offline-dummy", "replica");
        Started started = start(false);
        when(input.fromPerformanceSchema(any(), any(), any(), any())).thenThrow(new AssertionError("unused"));

        assertThatThrownBy(() -> started.loader().loadConfiguredSources("offline-dummy"))
            .hasMessageContaining("Unable to load sql");

        assertThat(started.environment().isCached()).isFalse();
        assertThat(liveReads).hasValue(2);
        assertThat(started.loader().health("offline-dummy").state()).isEqualTo(EnvironmentHealth.State.STALE);
    }

}
