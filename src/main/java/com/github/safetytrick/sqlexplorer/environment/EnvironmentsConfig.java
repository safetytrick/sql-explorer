package com.github.safetytrick.sqlexplorer.environment;

import java.util.LinkedHashMap;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the {@link EnvironmentRegistry} from {@code environments.*} config.
 * If no environments are configured, the registry falls back to a single
 * "default" environment built from the legacy {@code datasources.metadata.*}
 * keys (see docs/DATA_ENVIRONMENTS.md).
 *
 * <pre>
 * environments:
 *   staging:
 *     servers:
 *       staging-db-master:
 *         jdbcUrl: jdbc:mysql://...
 *         username: ...
 *         password: ...
 * </pre>
 */
@Configuration
class EnvironmentsConfig {

    /**
     * Binds {@code environments.<name>...} directly as a map (the env name is
     * the dynamic key). Declared as a {@code @Bean}-returned Map, the standard
     * Spring Boot idiom for binding a map at a prefix root.
     */
    @Bean
    @ConfigurationProperties(prefix = "environments")
    Map<String, EnvironmentDefinition> environmentDefinitions() {
        return new LinkedHashMap<>();
    }

    @Bean
    EnvironmentRegistry environmentRegistry(MetadataDataSourceProperties legacyMetadata,
                                            SchemaRepositoryFactory schemaRepositoryFactory) {
        return new EnvironmentRegistry(environmentDefinitions(), legacyMetadata, schemaRepositoryFactory);
    }
}
