package com.github.safetytrick.sqlexplorer.environment;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties("datasources.metadata")
public class MetadataDataSourceProperties {

    private String jdbcUrl;
    private String username;
    private String password;
}
