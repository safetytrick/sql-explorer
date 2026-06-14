package com.github.safetytrick.sqlexplorer.environment;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Legacy single-server connection config. Phase 1 of the environments work
 * builds the implicit "default" environment from these values; a multi-
 * environment {@code environments.*} config supersedes them in phase 3
 * (see docs/DATA_ENVIRONMENTS.md).
 */
@Getter
@Setter
@Component
@ConfigurationProperties("datasources.metadata")
public class MetadataDataSourceProperties {

    private String jdbcUrl;
    private String username;
    private String password;
}
