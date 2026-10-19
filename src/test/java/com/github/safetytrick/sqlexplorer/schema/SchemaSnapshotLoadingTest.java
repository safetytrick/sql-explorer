package com.github.safetytrick.sqlexplorer.schema;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A cluster's schema is read in full only when its fingerprint says the
 * snapshot no longer matches (sqlx-sqy.4).
 */
class SchemaSnapshotLoadingTest {

    @TempDir
    Path directory;

    private static final SchemaSnapshot.Key SHARD1 = new SchemaSnapshot.Key("production", "shard1");

    private final DataSource dataSource = mock(DataSource.class);
    private final SchemaFingerprintService fingerprints = mock(SchemaFingerprintService.class);
    private SchemaSnapshotStore store;
    private SchemaRepositoryFactory factory;

    private static SchemaFingerprint fingerprint(long hash) {
        return SchemaFingerprint.of(Map.of("app.users", Map.of("columns", hash)));
    }

    private static TableStructure users(String remarks) {
        return new TableStructure("app", "users", "TABLE", remarks, List.of(), List.of(), List.of(), null, null);
    }

    @BeforeEach
    void setUp() {
        store = new SchemaSnapshotStore(directory);
        factory = spy(new SchemaRepositoryFactory(null, null, null, mock(CreateStatementService.class), null,
            fingerprints, store));
    }

    /** The full read, standing in for the server: whatever it returns is what the server has. */
    private void serverHas(String remarks, long fingerprint) {
        doReturn(new LoadedSchema(List.of(users(remarks)))).when(factory).load(dataSource);
        when(fingerprints.fingerprint(dataSource)).thenReturn(fingerprint(fingerprint));
    }

    @Test
    void theFirstLoadReadsInFullAndLeavesASnapshot() {
        serverHas("v1", 1);

        SchemaRepositoryFactory.SchemaLoad load = factory.loadCurrent(dataSource, SHARD1, "shard1");

        assertThat(load.fromSnapshot()).isFalse();
        assertThat(load.current()).isTrue();
        assertThat(store.read(SHARD1)).hasValueSatisfying(snapshot -> {
            assertThat(snapshot.fingerprint()).isEqualTo(fingerprint(1));
            assertThat(snapshot.server()).isEqualTo("shard1");
            assertThat(snapshot.tables()).containsExactly(users("v1"));
        });
    }

    @Test
    void anUnchangedSchemaIsTakenFromTheSnapshotWithoutReadingIt() {
        serverHas("v1", 1);
        factory.loadCurrent(dataSource, SHARD1, "shard1");

        SchemaRepositoryFactory.SchemaLoad again = factory.loadCurrent(dataSource, SHARD1, "shard1-replica-a");

        verify(factory, times(1)).load(dataSource);
        assertThat(again.fromSnapshot()).isTrue();
        assertThat(again.current()).as("a matching fingerprint vouches for it").isTrue();
        assertThat(again.readFrom()).as("still the server it was read from").isEqualTo("shard1");
        assertThat(again.schema().getTables()).containsExactly(users("v1"));
    }

    @Test
    void aChangedSchemaIsReadAgainAndReplacesTheSnapshot() {
        serverHas("v1", 1);
        factory.loadCurrent(dataSource, SHARD1, "shard1");
        serverHas("v2", 2);

        SchemaRepositoryFactory.SchemaLoad changed = factory.loadCurrent(dataSource, SHARD1, "shard1");

        verify(factory, times(2)).load(dataSource);
        assertThat(changed.fromSnapshot()).isFalse();
        assertThat(changed.schema().getTables()).containsExactly(users("v2"));
        assertThat(store.read(SHARD1).orElseThrow().fingerprint()).isEqualTo(fingerprint(2));
    }

    /** With no server to confirm it, the snapshot still serves — marked as not current. */
    @Test
    void theLastSnapshotIsAvailableWithoutAConnectionButIsNotCurrent() {
        serverHas("v1", 1);
        factory.loadCurrent(dataSource, SHARD1, "shard1");

        assertThat(factory.lastKnown(SHARD1)).hasValueSatisfying(load -> {
            assertThat(load.current()).isFalse();
            assertThat(load.schema().getTables()).containsExactly(users("v1"));
        });
        assertThat(factory.lastKnown(new SchemaSnapshot.Key("production", "shard2"))).isEmpty();
    }

    @Test
    void stillCurrentComparesTheLiveFingerprint() {
        when(fingerprints.fingerprint(dataSource)).thenReturn(fingerprint(1));

        assertThat(factory.stillCurrent(dataSource, fingerprint(1))).isTrue();
        assertThat(factory.stillCurrent(dataSource, fingerprint(2))).isFalse();
        when(fingerprints.fingerprint(dataSource)).thenThrow(new IllegalStateException("unreachable"));
        assertThat(factory.stillCurrent(dataSource, fingerprint(1))).as("cannot tell is a no").isFalse();
    }
}
