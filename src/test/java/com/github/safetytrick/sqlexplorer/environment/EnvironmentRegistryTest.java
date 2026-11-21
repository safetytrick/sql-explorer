package com.github.safetytrick.sqlexplorer.environment;

import java.util.LinkedHashMap;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class EnvironmentRegistryTest {

    @Test
    void environmentMarkedDefaultWinsOverDeclarationOrder() {
        assertThat(registry(definitions("regression", "local", "local")).getDefaultName())
            .isEqualTo("local");
    }

    @Test
    void multipleDefaultsFail() {
        Map<String, EnvironmentDefinition> definitions = definitions("regression", "local", "regression");
        definitions.get("local").setDefault(true);

        assertThatThrownBy(() -> registry(definitions))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("regression")
            .hasMessageContaining("local");
    }

    @Test
    void firstDeclaredIsTheDefaultWhenNoneMarked() {
        assertThat(registry(definitions("regression", "local", null)).getDefaultName())
            .isEqualTo("regression");
    }

    @Test
    void environmentNamedDefaultWinsWhenNoneMarked() {
        assertThat(registry(definitions("regression", "default", null)).getDefaultName())
            .isEqualTo("default");
    }

    private static EnvironmentRegistry registry(Map<String, EnvironmentDefinition> definitions) {
        return new EnvironmentRegistry(definitions, null, mock(SchemaRepositoryFactory.class));
    }

    private static Map<String, EnvironmentDefinition> definitions(String first, String second, String markedDefault) {
        Map<String, EnvironmentDefinition> definitions = new LinkedHashMap<>();
        for (String name : new String[] {first, second}) {
            EnvironmentDefinition definition = new EnvironmentDefinition();
            definition.setDefault(name.equals(markedDefault));
            definitions.put(name, definition);
        }
        return definitions;
    }

    private static EnvironmentDefinition bind(Map<String, String> properties) {
        return new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(properties))
            .bind("environments.production", EnvironmentDefinition.class)
            .get();
    }

    private static Map<String, String> shard1(String... schemaFrom) {
        Map<String, String> properties = new LinkedHashMap<>();
        for (int i = 0; i < schemaFrom.length; i++) {
            properties.put("environments.production.clusters.shard1.schema-from[" + i + "]", schemaFrom[i]);
        }
        for (String server : new String[] {"shard1", "shard1-replica-a", "shard1-etl"}) {
            properties.put("environments.production.clusters.shard1.servers." + server + ".jdbc-url", "jdbc:mysql://" + server);
            properties.put("environments.production.clusters.shard1.servers." + server + ".sources[0].type", "x-statement-analysis");
        }
        return properties;
    }

    @Test
    void aClusterGroupsItsServersAndReadsSchemaOnlyFromSchemaFrom() {
        Environment production = registry(Map.of("production", bind(shard1("shard1", "shard1-replica-a"))))
            .get("production");

        assertThat(production.getClusters()).singleElement().satisfies(cluster -> {
            assertThat(cluster.name()).isEqualTo("shard1");
            assertThat(cluster.servers()).extracting(DatabaseServer::getName)
                .containsExactly("shard1", "shard1-replica-a", "shard1-etl");
            assertThat(cluster.schemaFrom()).extracting(DatabaseServer::getName)
                .containsExactly("shard1", "shard1-replica-a");
        });
        assertThat(production.getServers()).extracting(DatabaseServer::getName)
            .containsExactly("shard1", "shard1-replica-a", "shard1-etl");
    }

    @Test
    void aStandaloneServerIsItsOwnCluster() {
        Map<String, String> properties = new LinkedHashMap<>(shard1("shard1"));
        properties.put("environments.production.servers.global.jdbc-url", "jdbc:mysql://global");

        Environment production = registry(Map.of("production", bind(properties))).get("production");

        assertThat(production.getClusters()).extracting(Cluster::name).containsExactly("global", "shard1");
        Cluster global = production.getClusters().getFirst();
        assertThat(global.schemaFrom()).isEqualTo(global.servers());
    }

    @Test
    void schemaFromMustNameOneOfTheClustersServers() {
        Map<String, EnvironmentDefinition> definitions = Map.of("production", bind(shard1("shard2")));

        assertThatThrownBy(() -> registry(definitions))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("shard1")
            .hasMessageContaining("shard2");
    }

    @Test
    void aClusterWithoutSchemaFromFails() {
        Map<String, EnvironmentDefinition> definitions = Map.of("production", bind(shard1()));

        assertThatThrownBy(() -> registry(definitions))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("schemaFrom");
    }

    @Test
    void serverNamesAreUniqueAcrossClusters() {
        Map<String, String> properties = new LinkedHashMap<>(shard1("shard1"));
        properties.put("environments.production.clusters.shard2.schema-from[0]", "shard1");
        properties.put("environments.production.clusters.shard2.servers.shard1.jdbc-url", "jdbc:mysql://elsewhere");
        Map<String, EnvironmentDefinition> definitions = Map.of("production", bind(properties));

        assertThatThrownBy(() -> registry(definitions))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("more than once");
    }
}
