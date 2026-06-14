package com.github.safetytrick.sqlexplorer.environment;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

/**
 * Config for one environment (under {@code environments.<name>}): its database
 * servers keyed by server name.
 */
@Getter
@Setter
public class EnvironmentDefinition {
    private Map<String, ServerDefinition> servers = new LinkedHashMap<>();
}
