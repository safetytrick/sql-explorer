package com.github.safetytrick.sqlexplorer.environment;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

/**
 * Config for one environment (under {@code environments.<name>}): its database
 * servers keyed by server name, and whether it is the default environment
 * ({@code default: true} — declaration order is not reliable once several
 * config files contribute environments).
 */
@Getter
@Setter
public class EnvironmentDefinition {
    private Map<String, ServerDefinition> servers = new LinkedHashMap<>();
    /** Lombok names the accessors isDefault()/setDefault(), binding the {@code default} key. */
    private boolean isDefault;
}
