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

    @Test
    void oneReadableServerIsTheSchema() {
        LoadedSchema shard1 = mock(LoadedSchema.class);
        Environment environment = new Environment("production", List.of(readable(shard1), refusing()));

        assertThat(environment.schemaRepository()).isSameAs(shard1);
    }

    @Test
    void whenNoServerIsReadableTheFailureIsThrown() {
        Environment environment = new Environment("production", List.of(refusing(), refusing()));

        assertThatThrownBy(environment::schemaRepository).hasMessage("Access denied");
    }

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
