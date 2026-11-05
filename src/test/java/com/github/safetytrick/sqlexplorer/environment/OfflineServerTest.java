package com.github.safetytrick.sqlexplorer.environment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.github.safetytrick.sqlexplorer.schema.LoadedSchema;
import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import com.github.safetytrick.sqlexplorer.schema.SchemaSnapshot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * While an environment is served from its cache, none of its servers is
 * connected to: the schema comes from the snapshot and every pool request is
 * refused (sqlx-nbi.3, sqlx-nbi.4).
 */
class OfflineServerTest {

    private static final SchemaSnapshot.Key KEY = new SchemaSnapshot.Key("offline-dummy", "primary");

    private final SchemaRepositoryFactory factory = mock(SchemaRepositoryFactory.class);
    private final LoadedSchema snapshot = mock(LoadedSchema.class);

    private Environment environment() {
        when(factory.lastKnown(KEY)).thenReturn(Optional.of(new SchemaRepositoryFactory.SchemaLoad(snapshot, null,
            true, false, Instant.parse("2026-10-01T00:00:00Z"), "primary")));
        DatabaseServer server = new DatabaseServer("primary",
            new ConnectionInfo("jdbc:mysql://db.example.com:3306/", "reader", "pw"), List.of(), factory, KEY);
        return new Environment("offline-dummy", List.of(server));
    }

    @Test
    void aCachedEnvironmentRefusesEveryConnection() {
        Environment environment = environment();
        environment.setCached(true);
        DatabaseServer server = environment.getServers().getFirst();

        assertThatThrownBy(server::getDataSource).isInstanceOf(ServerOfflineException.class)
            .hasMessageContaining("pull the environment to go live");
    }

    /** The snapshot, not the fingerprint check that connects first, and marked unconfirmed. */
    @Test
    void itsSchemaComesFromTheSnapshotWithoutConnecting() {
        Environment environment = environment();
        environment.setCached(true);

        Cluster.SchemaSource source = environment.getClusters().getFirst().readSchema();

        assertThat(source.schema()).isSameAs(snapshot);
        assertThat(source.fromSnapshot()).isTrue();
        assertThat(source.current()).isFalse();
        verify(factory, never()).loadCurrent(any(), any(), any());
        environment.revalidateSchemas();
        verify(factory, never()).stillCurrent(any(), any());
    }

    /**
     * Going live is not remembered as a failure: the next access tries the
     * server. A refused local port stands in for it, so this fails fast.
     */
    @Test
    void goingLiveTriesTheServerAgain() {
        DatabaseServer server = new DatabaseServer("primary",
            new ConnectionInfo("jdbc:mysql://127.0.0.1:1/?connectTimeout=2000", "reader", "pw"), List.of(),
            factory, KEY);
        Environment environment = new Environment("offline-dummy", List.of(server));
        environment.setCached(true);
        assertThatThrownBy(server::getSchemaLoad).isInstanceOf(ServerOfflineException.class);

        environment.setCached(false);
        try (server) {
            assertThat(server.isOffline()).isFalse();
            assertThatThrownBy(server::getDataSource).isNotInstanceOf(ServerOfflineException.class);
        }
    }
}
