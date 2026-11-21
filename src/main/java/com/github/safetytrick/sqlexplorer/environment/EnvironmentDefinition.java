package com.github.safetytrick.sqlexplorer.environment;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EnvironmentDefinition {
    private Map<String, ServerDefinition> servers = new LinkedHashMap<>();
    private Map<String, ClusterDefinition> clusters = new LinkedHashMap<>();
    /** Lombok names the accessors isDefault()/setDefault(), binding the {@code default} key. */
    private boolean isDefault;

    private boolean explainEnabled;

    private String pullCron;
}
