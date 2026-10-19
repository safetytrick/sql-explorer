package com.github.safetytrick.sqlexplorer.environment;

import java.util.List;

import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A cluster reads its schema from schemaFrom, in order, and from nowhere else (sqlx-sqy.2). */
class ClusterSchemaTest {

    private static DatabaseServer server(String name) {
        DatabaseServer server = mock(DatabaseServer.class);
        when(server.getName()).thenReturn(name);
        return server;
    }

    private static DatabaseServer readable(String name, SqlSchemaRepository schema) {
        DatabaseServer server = server(name);
        when(server.getSchemaRepository()).thenReturn(schema);
        return server;
    }

    private static DatabaseServer refusing(String name) {
        DatabaseServer server = server(name);
        when(server.getSchemaRepository()).thenThrow(new IllegalStateException("Access denied for " + name));
        return server;
    }

    @Test
    void theFirstChoiceAnswersWhenItCan() {
        SqlSchemaRepository schema = mock(SqlSchemaRepository.class);
        DatabaseServer primary = readable("shard1", schema);
        DatabaseServer replica = readable("shard1-replica-a", mock(SqlSchemaRepository.class));
        DatabaseServer etl = server("shard1-etl");

        Cluster.SchemaSource source = new Cluster("shard1", List.of(primary, replica, etl),
            List.of(primary, replica)).readSchema();

        assertThat(source.schema()).isSameAs(schema);
        assertThat(source.server()).isEqualTo("shard1");
        assertThat(source.fallback()).isFalse();
        verify(replica, never()).getSchemaRepository();
        verify(etl, never()).getSchemaRepository();
    }

    @Test
    void anUnreachablePrimaryFallsBackToTheNextAndSaysWhy() {
        SqlSchemaRepository schema = mock(SqlSchemaRepository.class);
        DatabaseServer primary = refusing("shard1");
        DatabaseServer replica = readable("shard1-replica-a", schema);

        Cluster.SchemaSource source = new Cluster("shard1", List.of(primary, replica),
            List.of(primary, replica)).readSchema();

        assertThat(source.schema()).isSameAs(schema);
        assertThat(source.server()).isEqualTo("shard1-replica-a");
        assertThat(source.fallback()).isTrue();
        assertThat(source.passedOver()).containsExactly("shard1: Access denied for shard1");
    }

    /** A server outside schemaFrom is captured from but never read for schema, even when nothing else answers. */
    @Test
    void aServerOutsideSchemaFromIsNeverReadForSchema() {
        DatabaseServer primary = refusing("shard1");
        DatabaseServer etl = readable("shard1-etl", mock(SqlSchemaRepository.class));
        Cluster cluster = new Cluster("shard1", List.of(primary, etl), List.of(primary));

        assertThatThrownBy(cluster::readSchema).hasMessage("Access denied for shard1");
        verify(etl, never()).getSchemaRepository();
    }

    /** One schema per cluster; a cluster that cannot be read is left out and reported. */
    @Test
    void theEnvironmentReportsWhereEachClustersSchemaCameFrom() {
        DatabaseServer shard1 = refusing("shard1");
        DatabaseServer shard1Replica = readable("shard1-replica-a", mock(SqlSchemaRepository.class));
        DatabaseServer shard2 = refusing("shard2");
        Environment production = Environment.ofClusters("production", List.of(
            new Cluster("shard1", List.of(shard1, shard1Replica), List.of(shard1, shard1Replica)),
            new Cluster("shard2", List.of(shard2), List.of(shard2))), false, null);

        assertThat(production.schemaStatus()).containsExactly(
            new Environment.ClusterSchemaStatus("shard1", List.of("shard1", "shard1-replica-a"),
                "shard1-replica-a", true, List.of("shard1: Access denied for shard1"), null),
            new Environment.ClusterSchemaStatus("shard2", List.of("shard2"),
                null, false, List.of(), "Access denied for shard2"));
        assertThat(production.schemaRepository()).isNotNull();
    }
}
