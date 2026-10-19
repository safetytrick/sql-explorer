package com.github.safetytrick.sqlexplorer.environment;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

/**
 * Config for one environment (under {@code environments.<name>}): its database
 * servers keyed by server name, standalone or grouped into clusters, and whether it is the default environment
 * ({@code default: true} — declaration order is not reliable once several
 * config files contribute environments).
 */
@Getter
@Setter
public class EnvironmentDefinition {
    /** Servers that stand alone: each is its own cluster and its own schema source. */
    private Map<String, ServerDefinition> servers = new LinkedHashMap<>();
    /**
     * Servers grouped with the replicas that carry the same schema, read for
     * schema only through each cluster's {@code schemaFrom}. May be combined
     * with {@link #servers}; server names must be unique across both.
     */
    private Map<String, ClusterDefinition> clusters = new LinkedHashMap<>();
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

    /**
     * A Spring cron expression to pull this environment's sources on, or null
     * to pull only at startup and on request ({@code pullCron: "0 0 * * * *"}).
     *
     * <p>OFF UNLESS ASKED, like {@code explainEnabled} above and for a related
     * reason: a pull writes a capture file, and a deployment that never asked
     * for a schedule should not quietly start filling a disk.
     *
     * <p>PER ENVIRONMENT RATHER THAN ONE GLOBAL CADENCE, because environments
     * differ in the two things that decide how often a capture is worth taking:
     * how fast the digest table fills, and how often the server restarts and
     * clears it. A busy production server and a dev instance want different
     * answers (sqlx-ijz).
     */
    private String pullCron;
}
