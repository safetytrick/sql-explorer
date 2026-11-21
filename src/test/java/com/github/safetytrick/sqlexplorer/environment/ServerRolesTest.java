package com.github.safetytrick.sqlexplorer.environment;

import java.util.LinkedHashMap;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ServerRolesTest {

    private static Environment production(Map<String, String> properties) {
        EnvironmentDefinition definition = new Binder(new MapConfigurationPropertySource(properties))
            .bind("environments.production", EnvironmentDefinition.class)
            .get();
        return new EnvironmentRegistry(Map.of("production", definition), null, mock(SchemaRepositoryFactory.class))
            .get("production");
    }

    private static Map<String, String> servers(String primaryRoles, String replicaRoles) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("environments.production.servers.app-primary.jdbc-url", "jdbc:mysql://db.example.com:3306");
        properties.put("environments.production.servers.app-replica.jdbc-url",
            "jdbc:mysql://replica.db.example.com:3306");
        if (primaryRoles != null) {
            properties.put("environments.production.servers.app-primary.roles", primaryRoles);
        }
        if (replicaRoles != null) {
            properties.put("environments.production.servers.app-replica.roles", replicaRoles);
        }
        return properties;
    }

    @Test
    void rolesBindFromConfig() {
        Environment production = production(servers("primary", "replica,sampling"));

        assertThat(production.getServers().get(0).getRoles()).containsExactly(ServerRole.PRIMARY);
        assertThat(production.getServers().get(1).getRoles())
            .containsExactly(ServerRole.REPLICA, ServerRole.SAMPLING);
    }

    @Test
    void noRolesIsTheDefault() {
        Environment production = production(servers(null, null));

        assertThat(production.getServers()).allSatisfy(server -> assertThat(server.getRoles()).isEmpty());
    }

    @Test
    void theSamplingServerIsTheOneWithTheRole() {
        SamplingTarget target = production(servers("primary", "replica,sampling")).samplingTarget();

        assertThat(target.server().getName()).isEqualTo("app-replica");
        assertThat(target.environment()).isEqualTo("production");
        assertThat(target.limits()).isEqualTo(SamplingLimits.DEFAULT);
        assertThat(target.limits().maxExecutionTime()).isPositive();
        assertThat(target.limits().maxRows()).isPositive();
    }

    @Test
    void withoutTheRoleNothingIsSampledAndTheReasonIsGiven() {
        Environment production = production(servers("primary", "replica"));

        assertThatThrownBy(production::samplingTarget)
            .isInstanceOf(SamplingUnavailableException.class)
            .hasMessageContaining("production")
            .hasMessageContaining("no server with the sampling role");
        assertThat(production.samplingStatus()).satisfies(status -> {
            assertThat(status.server()).isNull();
            assertThat(status.available()).isFalse();
            assertThat(status.reason()).contains("sampling role");
        });
    }

    @Test
    void aCachedEnvironmentIsNeverSampled() {
        Environment production = production(servers("primary", "replica,sampling"));
        production.setCached(true);

        assertThatThrownBy(production::samplingTarget)
            .isInstanceOf(SamplingUnavailableException.class)
            .hasMessageContaining("served from its cache")
            .hasMessageContaining("app-replica");
        assertThat(production.samplingStatus()).satisfies(status -> {
            assertThat(status.server()).isEqualTo("app-replica");
            assertThat(status.available()).isFalse();
        });

        production.setCached(false);
        assertThat(production.samplingStatus().available()).as("live again").isTrue();
    }

    @Test
    void aPrimaryCannotAlsoBeTheSamplingServer() {
        assertThatThrownBy(() -> production(servers("primary,sampling", "replica")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("app-primary")
            .hasMessageContaining("primary cannot also have the sampling role");
    }

    @Test
    void anUnknownRoleFailsToBind() {
        assertThatThrownBy(() -> production(servers("writer", null)))
            .hasStackTraceContaining("writer");
    }

    @Test
    void limitsMustBePositive() {
        assertThatThrownBy(() -> new SamplingLimits(java.time.Duration.ZERO, 10))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SamplingLimits(java.time.Duration.ofSeconds(1), 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
