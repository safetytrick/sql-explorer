package com.github.safetytrick.sqlexplorer.environment;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import com.github.safetytrick.sqlexplorer.environment.Environment.ClusterSampling;
import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SamplingByClusterTest {

    private static final SamplingLimits LIMITS = new SamplingLimits(Duration.ofSeconds(2), 5_000);

    private static DatabaseServer server(String name, ServerRole... roles) {
        return new DatabaseServer(name, new ConnectionInfo("jdbc:mysql://db.example.com:3306", "reader", null),
            List.of(), mock(SchemaRepositoryFactory.class), null, Set.of(roles));
    }

    private static Environment twoShards() {
        DatabaseServer primary1 = server("shard1", ServerRole.PRIMARY);
        DatabaseServer replica1 = server("shard1-etl", ServerRole.REPLICA, ServerRole.SAMPLING);
        DatabaseServer primary2 = server("shard2", ServerRole.PRIMARY);
        DatabaseServer replica2 = server("shard2-replica", ServerRole.REPLICA);
        return Environment.ofClusters("production", List.of(
            new Cluster("shard1", List.of(primary1, replica1), List.of(primary1)),
            new Cluster("shard2", List.of(primary2, replica2), List.of(primary2))), false, null);
    }

    @Test
    void eachClusterIsSampledThroughItsOwnSamplingServerOrSaysWhyNot() {
        List<ClusterSampling> sampling = twoShards().samplingByCluster(LIMITS);

        assertThat(sampling).hasSize(2);
        assertThat(sampling.get(0).target().server().getName()).isEqualTo("shard1-etl");
        assertThat(sampling.get(0).target().limits()).isEqualTo(LIMITS);
        assertThat(sampling.get(1).target()).isNull();
        assertThat(sampling.get(1).reason()).contains("shard2").contains("no server with the sampling role");
    }

    @Test
    void aCachedEnvironmentSamplesNothing() {
        Environment environment = twoShards();
        environment.setCached(true);

        assertThat(environment.samplingByCluster(LIMITS)).allSatisfy(s -> {
            assertThat(s.target()).isNull();
            assertThat(s.reason()).isNotNull();
        });
        assertThatThrownBy(() -> environment.samplingTargetOn("shard1-etl", LIMITS))
            .isInstanceOf(SamplingUnavailableException.class).hasMessageContaining("cache");
    }

    @Test
    void aProbeAddressesOneServerOnlyIfItMayBeSampled() {
        Environment environment = twoShards();

        assertThat(environment.samplingTargetOn("shard1-etl", LIMITS).server().getName()).isEqualTo("shard1-etl");
        assertThatThrownBy(() -> environment.samplingTargetOn("shard2-replica", LIMITS))
            .isInstanceOf(SamplingUnavailableException.class).hasMessageContaining("sampling role");
        assertThatThrownBy(() -> environment.samplingTargetOn("nowhere", LIMITS))
            .isInstanceOf(SamplingUnavailableException.class);
    }
}
