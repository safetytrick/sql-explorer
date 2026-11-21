package com.github.safetytrick.sqlexplorer.environment;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.schema.LoadedSchema;
import com.github.safetytrick.sqlexplorer.schema.SchemaFingerprint;
import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import com.github.safetytrick.sqlexplorer.schema.TableDiff;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchemaDriftTest {

    private static Cluster cluster(String name, Map<String, String> tables) {
        DatabaseServer server = mock(DatabaseServer.class);
        when(server.getName()).thenReturn(name);
        when(server.getSchemaLoad()).thenReturn(new SchemaRepositoryFactory.SchemaLoad(mock(LoadedSchema.class),
            tables == null ? null : new SchemaFingerprint(tables), false, true, Instant.EPOCH, name));
        return Cluster.of(server);
    }

    private static Cluster cluster(String name, Map<String, String> tables, LoadedSchema schema) {
        DatabaseServer server = mock(DatabaseServer.class);
        when(server.getName()).thenReturn(name);
        when(server.getSchemaLoad()).thenReturn(new SchemaRepositoryFactory.SchemaLoad(schema,
            new SchemaFingerprint(tables), false, true, Instant.EPOCH, name));
        return Cluster.of(server);
    }

    private static LoadedSchema schema(String ddl) throws Exception {
        return new LoadedSchema(List.of(TableStructure.fromCreateTable(ddl, "app_production").build()));
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

    @Test
    void aTableMissingFromAShardIsAVariantOfItsOwn() {
        Environment production = production(
            cluster("shard1", Map.of("app_production.users", "a", "app_production.audit", "x")),
            cluster("shard2", Map.of("app_production.users", "a")));

        assertThat(production.schemaDrift()).containsExactly(new Environment.TableDrift("app_production.audit",
            List.of(new Environment.Variant(List.of("shard1"), true),
                new Environment.Variant(List.of("shard2"), false))));
    }

    @Test
    void aDifferingVariantSaysWhatDiffersFromTheOneLookupsUse() throws Exception {
        Environment production = production(
            cluster("shard1", Map.of("app_production.users", "a"),
                schema("CREATE TABLE users (id BIGINT NOT NULL, PRIMARY KEY (id))")),
            cluster("shard2", Map.of("app_production.users", "a2"),
                schema("CREATE TABLE users (id BIGINT NOT NULL, email VARCHAR(255), PRIMARY KEY (id))")));

        assertThat(production.schemaDrift()).containsExactly(new Environment.TableDrift("app_production.users",
            List.of(new Environment.Variant(List.of("shard1"), true),
                new Environment.Variant(List.of("shard2"), true, List.of(
                    new TableDiff.Difference("column", "email", null, "varchar(255) NULL · position 2"))))));
    }

    @Test
    void differentSchemasAreNotCompared() {
        Environment production = production(
            cluster("shard1", Map.of("app_production.users", "a")),
            cluster("global", Map.of("app_global.accounts", "z")));

        assertThat(production.schemaDrift()).isEmpty();
    }

    @Test
    void aClusterWithoutAFingerprintTakesNoPart() {
        Environment production = production(
            cluster("shard1", Map.of("app_production.users", "a")),
            cluster("shard2", null));

        assertThat(production.schemaDrift()).isEmpty();
    }
}
