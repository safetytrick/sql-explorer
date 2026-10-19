package com.github.safetytrick.sqlexplorer.evidence;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlInput;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Keeping heap bounded across many environments.
 *
 * <p>The pressure is not one big corpus — measured at 32 MB for 4,244 profiles
 * against a 512 MB heap — it is N environments each holding one at once. Five
 * at 10,000 profiles is ~380 MB (sqlx-4ww).
 *
 * <p>Two failures matter more than the arithmetic: evicting the environment
 * someone is working in, and evicting one that cannot come back.
 */
class EnvironmentEvictionTest {

    private CaptureRehydrator rehydrator;
    private SqlRepositoryManager manager;
    private final AtomicInteger rehydrations = new AtomicInteger();

    /** A clock that advances a second per read, so access order is unambiguous. */
    private static Clock ticking() {
        return new Clock() {
            private Instant now = Instant.parse("2026-08-31T00:00:00Z");

            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public synchronized Instant instant() {
                now = now.plusSeconds(1);
                return now;
            }
        };
    }

    private static SqlSource source(String ref) {
        return new SqlSource() {
            @Override public Stream<SqlInput> getSql() { return Stream.empty(); }
            @Override public Instant getCreatedOn() { return Instant.EPOCH; }
            @Override public String getSourceRef() { return ref; }
        };
    }

    private static SqlRepository repositoryFor(String ref) {
        return new SqlRepository() {
            @Override public SqlProfile getByDigest(String id) { return null; }
            @Override public Stream<SqlProfile> getTableQueries(TableReference t) { return Stream.empty(); }
            @Override public Stream<SqlProfile> getQueriesForColumn(TableReference t, String c) { return Stream.empty(); }
            @Override public Stream<TableReference> getAllTableReferences() { return Stream.empty(); }
            @Override public Stream<SqlProfile> getAllAnalyzedSql() { return Stream.empty(); }
            @Override public SqlSource getSource() { return source(ref); }
            @Override public SqlImportStats getImportStats() { return SqlImportStats.builder().build(); }
            @Override public CaptureContext getCaptureContext() { return null; }
        };
    }

    private void load(String environment, String sourceRef) {
        manager.loadFromSqlSources(environment, Stream.of(source(sourceRef)));
    }

    @BeforeEach
    void setUp() {
        SqlEvidenceInputService input = mock(SqlEvidenceInputService.class);
        when(input.fromPerformanceSchema(org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
            .thenAnswer(call -> repositoryFor(((SqlSource) call.getArgument(0)).getSourceRef()));

        EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
        when(registry.get(anyString())).thenReturn(mock(com.github.safetytrick.sqlexplorer.environment.Environment.class));

        rehydrator = mock(CaptureRehydrator.class);
        when(rehydrator.canRehydrate(anyString())).thenReturn(true);
        when(rehydrator.rehydrate(anyString())).thenAnswer(call -> {
            rehydrations.incrementAndGet();
            return repositoryFor(call.getArgument(0));
        });

        manager = new SqlRepositoryManager(input, registry, rehydrator, 2, ticking());
    }

    @Test
    void anEnvironmentBeyondTheCapIsDropped() {
        load("a", "src-a");
        load("b", "src-b");
        load("c", "src-c");

        assertThat(manager.getLoadState("a").resident()).isFalse();
        assertThat(manager.getLoadState("b").resident()).isTrue();
        assertThat(manager.getLoadState("c").resident()).isTrue();
    }

    /**
     * Evicted is not unloaded. The listing enumerates every environment, and
     * saying "not loaded" for one that comes back on the next read would send
     * someone to re-import something already there.
     */
    @Test
    void anEvictedEnvironmentStillReportsAsLoaded() {
        load("a", "src-a");
        load("b", "src-b");
        load("c", "src-c");

        assertThat(manager.getLoadState("a")).isNotNull();
        assertThat(manager.getLoadState("a").sourceCount()).isEqualTo(1);
        assertThat(manager.getLoadState("never-loaded")).isNull();
    }

    /** Reading it back rebuilds it, and the caller cannot tell. */
    @Test
    void readingAnEvictedEnvironmentRebuildsIt() {
        load("a", "src-a");
        load("b", "src-b");
        load("c", "src-c");
        assertThat(manager.getLoadState("a").resident()).isFalse();

        assertThat(manager.getRepositories("a")).hasSize(1);

        assertThat(manager.getLoadState("a").resident()).isTrue();
        assertThat(rehydrations.get()).isEqualTo(1);
    }

    /**
     * The least recently USED goes, not the least recently loaded. Touching an
     * environment has to protect it, or the one being worked in is the one
     * dropped.
     */
    @Test
    void theLeastRecentlyUsedIsTheOneDropped() {
        load("a", "src-a");
        load("b", "src-b");
        manager.getRepositories("a");
        load("c", "src-c");

        assertThat(manager.getLoadState("b").resident()).as("b is now the oldest read").isFalse();
        assertThat(manager.getLoadState("a").resident()).isTrue();
    }

    /**
     * What was just loaded is never the thing evicted to make room for itself.
     */
    @Test
    void theEnvironmentJustLoadedIsNeverTheOneDropped() {
        load("a", "src-a");
        load("b", "src-b");
        load("c", "src-c");

        assertThat(manager.getLoadState("c").resident()).isTrue();
    }

    /**
     * THE GUARD. An environment with no usable capture can only come back by
     * re-importing, which needs the analysed server to be reachable — and it
     * may not be. Bounded memory is not worth a corpus that cannot return, so
     * the cap is a target rather than a guarantee.
     */
    @Test
    void anEnvironmentThatCannotBeRebuiltIsKeptHoweverOldItIs() {
        when(rehydrator.canRehydrate("src-a")).thenReturn(false);

        load("a", "src-a");
        load("b", "src-b");
        load("c", "src-c");

        assertThat(manager.getLoadState("a").resident())
            .as("kept despite being oldest — nothing could bring it back").isTrue();
        assertThat(manager.getLoadState("b").resident()).isFalse();
    }

    /** With eviction switched off, nothing is ever dropped. */
    @Test
    void aCapOfZeroDisablesEviction() {
        manager = new SqlRepositoryManager(mock(SqlEvidenceInputService.class),
            mock(EnvironmentRegistry.class), rehydrator, 0, ticking());
        SqlEvidenceInputService input = mock(SqlEvidenceInputService.class);
        when(input.fromPerformanceSchema(org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
            .thenAnswer(call -> repositoryFor(((SqlSource) call.getArgument(0)).getSourceRef()));
        EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
        when(registry.get(anyString())).thenReturn(mock(com.github.safetytrick.sqlexplorer.environment.Environment.class));
        manager = new SqlRepositoryManager(input, registry, rehydrator, 0, ticking());

        load("a", "src-a");
        load("b", "src-b");
        load("c", "src-c");
        load("d", "src-d");

        assertThat(manager.getLoadState("a").resident()).isTrue();
    }

    /**
     * A burst against an evicted environment rebuilds it once, not once per
     * request. Without the guard this is the thundering herd, and it lands
     * exactly when the process is already busy.
     */
    @Test
    void concurrentReadsRehydrateOnlyOnce() throws Exception {
        load("a", "src-a");
        load("b", "src-b");
        load("c", "src-c");
        assertThat(manager.getLoadState("a").resident()).isFalse();
        rehydrations.set(0);

        int readers = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(readers);
        ExecutorService pool = Executors.newFixedThreadPool(readers);
        try {
            for (int i = 0; i < readers; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        manager.getRepositories("a");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(rehydrations.get()).isEqualTo(1);
    }

    /**
     * A refused rehydration serves nothing rather than an empty corpus dressed
     * as a real one. The caller's answer is to load the environment again.
     */
    @Test
    void anEnvironmentThatRefusesToRehydrateServesNothing() {
        load("a", "src-a");
        load("b", "src-b");
        load("c", "src-c");
        when(rehydrator.rehydrate("src-a")).thenReturn(null);

        assertThat(manager.getRepositories("a")).isEmpty();
        assertThat(manager.getLoadState("a").resident()).isFalse();
    }

    /** Rehydration is all-or-nothing: a half-built environment says nothing about being half. */
    @Test
    void rehydrationOfSeveralSourcesIsAllOrNothing() {
        assertThat(manager.rehydrate("multi", List.of("src-a", "src-b"))).isTrue();

        when(rehydrator.rehydrate("src-b")).thenReturn(null);
        assertThat(manager.rehydrate("other", List.of("src-a", "src-b"))).isFalse();
        assertThat(manager.getLoadState("other")).isNull();
    }
}
