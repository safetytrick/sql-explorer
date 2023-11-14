package com.github.safetytrick.sqlexplorer.schema;

import javax.sql.DataSource;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariDataSource;

@Configuration
public class MetadataDataSource {

    public static final String NAME = "metadata";

    @Bean(name = NAME)
    @ConfigurationProperties(prefix="datasources.metadata")
    public DataSource metadataDataSource() {
        return new HikariDataSource();
    }

}
