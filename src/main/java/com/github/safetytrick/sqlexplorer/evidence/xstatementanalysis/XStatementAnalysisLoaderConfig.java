package com.github.safetytrick.sqlexplorer.evidence.xstatementanalysis;

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
class XStatementAnalysisLoaderConfig {
    private boolean enabled = false;

    private Map<String, MySqlStatementAnalysisHost> hosts = new HashMap<>();
}
