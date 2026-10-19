package com.github.safetytrick.sqlexplorer.environment;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.schema.LoadedSchema;
import com.github.safetytrick.sqlexplorer.schema.SchemaFingerprint;
import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Clusters carrying the same schema are compared table by table, so a
 * migration applied to some shards and not others is visible (sqlx-sqy.7).
 */
class SchemaDriftTest {

    private static Cluster cluster(String name, Map<String, String> tables) {
        DatabaseServer server = mock(DatabaseServer.class);
        when(server.getName()).thenReturn(name);
        when(server.getSchemaLoad()).thenReturn(new SchemaRepositoryFactory.SchemaLoad(mock(LoadedSchema.class),
            tables == null ? null : new SchemaFingerprint(tables), false, true, Instant.EPOCH, name));
        return Cluster.of(server);
    }

    private static Environment production(Cluster... clusters) {
        return Environment.ofClusters("production", List.of(clusters), false, null);
    }

    @Test
    void shardsThatAgreeShowNoDrift() {
        Map<String, String> same = Map.of("app_production.users", "a", "app_production.jobs", "b");

        assertThat(production(cluster("shard1", same), cluster("shard2", same)).schemaDrift()).isEmpty();
    }

    @Test
    void aTableOneShardDefinesDifferentlyIsNamedWithWhoHasWhich() {
        Environment production = production(
            cluster("shard1", Map.of("app_production.users", "a", "app_production.jobs", "b")),
            cluster("shard2", Map.of("app_production.users", "a2", "app_production.jobs", "b")),
            cluster("shard3", Map.of("app_production.users", "a", "app_production.jobs", "b")));

        assertThat(production.schemaDrift()).containsExactly(new Environment.TableDrift("app_production.users",
            List.of(new Environment.Variant(List.of("shard1", "shard3"), true),
                new Environment.Variant(List.of("shard2"), true))));
    }

    /** A table only some shards have — a migration that created it on some — is drift too. */
    @Test
    void aTableMissingFromAShardIsAVariantOfItsOwn() {
        Environment production = production(
            cluster("shard1", Map.of("app_production.users", "a", "app_production.audit", "x")),
            cluster("shard2", Map.of("app_production.users", "a")));

        assertThat(production.schemaDrift()).containsExactly(new Environment.TableDrift("app_production.audit",
            List.of(new Environment.Variant(List.of("shard1"), true),
                new Environment.Variant(List.of("shard2"), false))));
    }

    /** global's schema is not shard1's: only clusters carrying the same schema are compared. */
    @Test
    void differentSchemasAreNotCompared() {
        Environment production = production(
            cluster("shard1", Map.of("app_production.users", "a")),
            cluster("global", Map.of("app_global.accounts", "z")));

        assertThat(production.schemaDrift()).isEmpty();
    }

    /** No fingerprint, nothing to compare — not "no drift" claimed on its behalf, just left out. */
    @Test
    void aClusterWithoutAFingerprintTakesNoPart() {
        Environment production = production(
            cluster("shard1", Map.of("app_production.users", "a")),
            cluster("shard2", null));

        assertThat(production.schemaDrift()).isEmpty();
    }
}
