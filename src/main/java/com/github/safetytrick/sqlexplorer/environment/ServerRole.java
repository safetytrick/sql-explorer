package com.github.safetytrick.sqlexplorer.environment;

import java.util.Locale;

/**
 * What a server is for, declared per server in config
 * ({@code environments.<env>.servers.<name>.roles: [replica, sampling]}),
 * sqlx-n5j.4.
 *
 * <p>Four roles, deliberately few. The first three say what the
 * <em>application</em> uses the server for; the last is the one that grants
 * SQL Explorer something:
 *
 * <ul>
 *   <li>{@link #PRIMARY} — takes the application's writes.</li>
 *   <li>{@link #REPLICA} — serves the application's reads.</li>
 *   <li>{@link #ANALYTICS} — a replica the application does not read from:
 *       ETL, reporting, analysts.</li>
 *   <li>{@link #SAMPLING} — SQL Explorer may run its own queries against
 *       table data here (profiling, cardinality estimates, samples), always
 *       through {@link Environment#samplingTarget()} and within its
 *       {@link SamplingLimits}.</li>
 * </ul>
 *
 * <p>Roles describe; they change nothing about what is read today. Schema
 * reads and {@code performance_schema} captures go where they always went
 * ({@code schemaFrom}, every server). Only {@code sampling} gates anything, and
 * it is opt-in: a server with no roles, or an environment no server of which
 * says {@code sampling}, is never sampled. A {@code primary} can never also be
 * {@code sampling} — the registry refuses that config at startup.
 *
 * <p>Written lowercase in config and in {@code /environments}; Spring's binder
 * accepts any case.
 */
public enum ServerRole {
    PRIMARY,
    REPLICA,
    ANALYTICS,
    SAMPLING;

    /** The name as config and the API write it: {@code sampling}. */
    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
