package com.github.safetytrick.sqlexplorer.environment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.github.safetytrick.sqlexplorer.schema.LoadedSchema;
import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import com.github.safetytrick.sqlexplorer.schema.SchemaSnapshot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LastKnownSchemaTest {

    private static final SchemaSnapshot.Key SHARD1 = new SchemaSnapshot.Key("production", "shard1");

    @Test
    void theLastSnapshotIsReadOnceUntilALoadAsksAgain() {
        SchemaRepositoryFactory factory = mock(SchemaRepositoryFactory.class);
        when(factory.lastKnown(SHARD1)).thenReturn(Optional.of(new SchemaRepositoryFactory.SchemaLoad(
            mock(LoadedSchema.class), null, true, false, Instant.EPOCH, "shard1")));
        DatabaseServer server = new DatabaseServer("shard1", new ConnectionInfo("jdbc:x", "u", "p"), List.of(),
            factory, SHARD1);

        Optional<SchemaRepositoryFactory.SchemaLoad> first = server.lastKnownSchema();
        Optional<SchemaRepositoryFactory.SchemaLoad> second = server.lastKnownSchema();

        assertThat(second).isSameAs(first);
        verify(factory, times(1)).lastKnown(SHARD1);

        server.refreshSchema();
        server.lastKnownSchema();
        verify(factory, times(2)).lastKnown(SHARD1);
    }
}
