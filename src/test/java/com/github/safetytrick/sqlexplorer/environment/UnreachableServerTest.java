package com.github.safetytrick.sqlexplorer.environment;

import java.util.List;

import com.github.safetytrick.sqlexplorer.schema.CompositeSchemaRepository;
import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import com.github.safetytrick.sqlexplorer.schema.LoadedSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchRuntimeException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A server whose schema cannot be read is left out of its environment's
 * schema, not allowed to fail it (sqlx-jdf).
 */
class UnreachableServerTest {

    private static SchemaRepositoryFactory.SchemaLoad load(LoadedSchema schema) {
        return new SchemaRepositoryFactory.SchemaLoad(schema, null, false, true,
            java.time.Instant.parse("2026-10-19T17:00:00Z"), null);
    }

    private static DatabaseServer readable(LoadedSchema schema) {
        DatabaseServer server = mock(DatabaseServer.class);
        when(server.getSchemaLoad()).thenReturn(load(schema));
        return server;
    }

    private static DatabaseServer refusing() {
        DatabaseServer server = mock(DatabaseServer.class);
        when(server.getSchemaLoad()).thenThrow(new IllegalStateException("Access denied"));
        return server;
    }

    @Test
    void theReadableServersMakeUpTheSchema() {
        LoadedSchema shard1 = mock(LoadedSchema.class);
        LoadedSchema shard2 = mock(LoadedSchema.class);
        Environment environment = new Environment("production",
            List.of(readable(shard1), refusing(), readable(shard2)));

        assertThat(environment.schemaRepository()).isInstanceOf(CompositeSchemaRepository.class);
    }

    /** One left standing is returned as itself, as a single-server environment always was. */
    @Test
    void oneReadableServerIsTheSchema() {
        LoadedSchema shard1 = mock(LoadedSchema.class);
        Environment environment = new Environment("production", List.of(readable(shard1), refusing()));

        assertThat(environment.schemaRepository()).isSameAs(shard1);
    }

    /** With nothing readable there is no schema, and the failure is the answer — as before. */
    @Test
    void whenNoServerIsReadableTheFailureIsThrown() {
        Environment environment = new Environment("production", List.of(refusing(), refusing()));

        assertThatThrownBy(environment::schemaRepository).hasMessage("Access denied");
    }

    /**
     * A failed load is remembered: a pool that cannot start takes seconds to
     * say so, and every request touching the schema would otherwise pay that
     * again. Forgetting it — what a load or pull does — tries the server anew.
     *
     * <p>Uses a real refused connection, so it exercises the pool failure the
     * production server produced rather than a stand-in.
     */
    @Test
    void aFailedSchemaLoadIsRememberedUntilForgotten() {
        DatabaseServer server = new DatabaseServer("sharing",
            new ConnectionInfo("jdbc:mysql://127.0.0.1:1/?connectTimeout=2000", "reader", "pw"),
            List.of(), mock(SchemaRepositoryFactory.class));
        try (server) {
            RuntimeException first = catchRuntimeException(server::getSchemaRepository);
            RuntimeException second = catchRuntimeException(server::getSchemaRepository);
            assertThat(first).isNotNull();
            assertThat(second).as("the remembered failure, not a second attempt").isSameAs(first);

            server.forgetSchemaFailure();
            RuntimeException third = catchRuntimeException(server::getSchemaRepository);
            assertThat(third).as("a fresh attempt").isNotNull().isNotSameAs(first);
        }
    }
}
