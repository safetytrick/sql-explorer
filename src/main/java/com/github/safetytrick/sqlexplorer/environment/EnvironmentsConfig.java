package com.github.safetytrick.sqlexplorer.environment;

import java.util.LinkedHashMap;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class EnvironmentsConfig {

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
