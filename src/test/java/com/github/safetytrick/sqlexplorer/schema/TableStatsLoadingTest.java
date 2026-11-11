package com.github.safetytrick.sqlexplorer.schema;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Table stats ride along with every schema load: read beside the fingerprint,
 * attached to the tables, and kept in the snapshot so a cached start has them
 * (sqlx-n5j.2).
 */
class TableStatsLoadingTest {

    @TempDir
    Path directory;

    private static final SchemaSnapshot.Key SHARD1 = new SchemaSnapshot.Key("production", "shard1");
    private static final TableReference ACCOUNT = TableReference.newTableReference("app_core", "account");

    private final DataSource dataSource = mock(DataSource.class);
    private final SchemaFingerprintService fingerprints = mock(SchemaFingerprintService.class);
    private final TableStatsService statsService = mock(TableStatsService.class);
    private SchemaSnapshotStore store;
    private SchemaRepositoryFactory factory;

    private static TableStats stats(String server, long rows) {
        return new TableStats(server, Instant.parse("2026-11-01T00:00:00Z"), rows, rows * 100, rows * 20, 0L, 100L,
            rows + 1, "Dynamic", "InnoDB", null, null, "utf8mb4_0900_ai_ci");
    }

    @BeforeEach
    void setUp() {
        store = new SchemaSnapshotStore(directory);
        factory = spy(new SchemaRepositoryFactory(null, null, null, mock(CreateStatementService.class), null,
            fingerprints, store, statsService));
        doReturn(new LoadedSchema(List.of(
            new TableStructure("app_core", "account", "TABLE", "", List.of(), List.of(), List.of(), null, null),
            new TableStructure("app_core", "account_view", "VIEW", "", List.of(), List.of(), List.of(), null, null))))
            .when(factory).load(dataSource);
        when(fingerprints.fingerprint(dataSource))
            .thenReturn(SchemaFingerprint.of(Map.of("app_core.account", Map.of("columns", 1L))));
    }

    @Test
    void aFullReadAttachesTheStatsAndSnapshotsThem() {
        when(statsService.read(dataSource, "shard1")).thenReturn(Map.of("app_core.account", stats("shard1", 10)));

        SchemaRepositoryFactory.SchemaLoad load = factory.loadCurrent(dataSource, SHARD1, "shard1");

        assertThat(load.schema().getTable(ACCOUNT).stats()).containsExactly(stats("shard1", 10));
        assertThat(load.schema().getTable(TableReference.newTableReference("app_core", "account_view")).stats())
            .as("a view has no figures").isNull();
        assertThat(store.read(SHARD1).orElseThrow().tables().getFirst().stats())
            .containsExactly(stats("shard1", 10));
    }

    /** The structure has not changed, but the data has: the snapshot is reused and its stats replaced. */
    @Test
    void anUnchangedSchemaStillGetsFreshStats() {
        when(statsService.read(dataSource, "shard1")).thenReturn(Map.of("app_core.account", stats("shard1", 10)));
        factory.loadCurrent(dataSource, SHARD1, "shard1");
        when(statsService.read(dataSource, "shard1")).thenReturn(Map.of("app_core.account", stats("shard1", 25)));

        SchemaRepositoryFactory.SchemaLoad again = factory.loadCurrent(dataSource, SHARD1, "shard1");

        verify(factory, times(1)).load(dataSource);
        assertThat(again.fromSnapshot()).isTrue();
        assertThat(again.schema().getTable(ACCOUNT).rowsEstimate()).isEqualTo(25L);
        assertThat(factory.lastKnown(SHARD1).orElseThrow().schema().getTable(ACCOUNT).rowsEstimate())
            .as("what a cached start would be served").isEqualTo(25L);
    }

    @Test
    void aPullThatKeepsTheSchemaRefreshesItsStats() {
        when(statsService.read(dataSource, "shard1")).thenReturn(Map.of("app_core.account", stats("shard1", 10)));
        SchemaRepositoryFactory.SchemaLoad loaded = factory.loadCurrent(dataSource, SHARD1, "shard1");
        when(statsService.read(dataSource, "shard1")).thenReturn(Map.of("app_core.account", stats("shard1", 40)));

        SchemaRepositoryFactory.SchemaLoad fresh = factory.withFreshStats(dataSource, loaded, SHARD1, "shard1");

        assertThat(fresh.schema().getTable(ACCOUNT).rowsEstimate()).isEqualTo(40L);
        assertThat(fresh.readAt()).as("the schema itself was not re-read").isEqualTo(loaded.readAt());
        assertThat(store.read(SHARD1).orElseThrow().tables().getFirst().rowsEstimate()).isEqualTo(40L);
    }

    /** Stats are an extra: failing to read them costs the stats, not the schema. */
    @Test
    void statsThatCannotBeReadLeaveTheSchemaWithoutThem() {
        when(statsService.read(any(), any())).thenThrow(new IllegalStateException("denied"));

        SchemaRepositoryFactory.SchemaLoad load = factory.loadCurrent(dataSource, SHARD1, "shard1");

        assertThat(load.schema().getTable(ACCOUNT)).isNotNull();
        assertThat(load.schema().getTable(ACCOUNT).stats()).isNull();
        assertThat(factory.withFreshStats(dataSource, load, SHARD1, "shard1")).isSameAs(load);
    }

    /** Each shard reports its own rows; an environment's answer carries all of them, and the totals add up. */
    @Test
    void aTableOnSeveralClustersCarriesEachClustersStats() {
        TableStructure onShard1 = new TableStructure("app_core", "account", "TABLE", "", List.of(), List.of(),
            List.of(), null, null, List.of(stats("shard1", 10)));
        TableStructure onShard2 = onShard1.withStats(List.of(stats("shard2", 5)));
        CompositeSchemaRepository composite = new CompositeSchemaRepository(List.of(
            new LoadedSchema(List.of(onShard1)), new LoadedSchema(List.of(onShard2))));

        TableStructure account = composite.getTable(ACCOUNT);

        assertThat(account.stats()).extracting(TableStats::server).containsExactly("shard1", "shard2");
        assertThat(account.rowsEstimate()).isEqualTo(15L);
        assertThat(account.sizeBytes()).isEqualTo(15L * 120);
        assertThat(composite.getTables()).singleElement()
            .satisfies(table -> assertThat(table.rowsEstimate()).isEqualTo(15L));
    }
}
