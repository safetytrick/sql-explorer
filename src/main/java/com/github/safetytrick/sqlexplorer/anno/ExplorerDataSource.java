package com.github.safetytrick.sqlexplorer.anno;

import javax.sql.DataSource;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import com.zaxxer.hikari.HikariDataSource;

@Configuration
@EnableJpaAuditing
public class ExplorerDataSource {

    public static final String NAME = "explorer";

    @Bean(name = NAME)
    @Primary
    @ConfigurationProperties(prefix="datasources.explorer")
    public DataSource explorerDataSource() {
        return new HikariDataSource();
    }


}
