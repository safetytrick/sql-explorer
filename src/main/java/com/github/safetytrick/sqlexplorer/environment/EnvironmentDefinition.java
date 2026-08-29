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

    /**
     * Whether this environment may have {@code EXPLAIN} run against its servers
     * ({@code explainEnabled: true}).
     *
     * <p><b>Off unless it says otherwise, and that default is the feature's
     * safety property rather than a convenience.</b> Everything else this
     * application does against an analyzed server is a read of the server's own
     * bookkeeping — schema metadata and {@code performance_schema}. Running
     * {@code EXPLAIN} is the one thing that puts a statement of ours in front of
     * a customer's data, so an environment has to opt in by name. A config file
     * that forgets to mention this cannot accidentally enable it.
     */
    private boolean explainEnabled;
}
