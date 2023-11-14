package com.github.safetytrick.sqlexplorer.performanceschema.xstatementanalysis;

import java.util.HashMap;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties("x-statement-analysis")
public class XStatementAnalysisLoaderConfig {
    private boolean enabled = false;

    private Map<String, MySqlStatementAnalysisHost> hosts = new HashMap<>();
}
