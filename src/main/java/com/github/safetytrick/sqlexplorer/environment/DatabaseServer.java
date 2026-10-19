package com.github.safetytrick.sqlexplorer.environment;

import javax.sql.DataSource;
import java.util.List;

import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import com.github.safetytrick.sqlexplorer.schema.SchemaSnapshot;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One MySQL server inside an {@link Environment}. Owns its connection pool and
 * the schema loaded from it; both are built lazily on first use so the
 * application can start without reaching any metadata database. Also carries
 * the {@link SourceDefinition}s describing what SQL to ingest from it.
 */
public final class DatabaseServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DatabaseServer.class);

    private final String name;
    private final ConnectionInfo connectionInfo;
    private final List<SourceDefinition> sourceDefinitions;
    private final SchemaRepositoryFactory schemaRepositoryFactory;
    /** The cluster whose schema snapshot this server reads and writes; null for no snapshots. */
    private final SchemaSnapshot.Key snapshotKey;

    private HikariDataSource dataSource;
    private SchemaRepositoryFactory.SchemaLoad schemaLoad;
    /**
     * Why the last schema load failed, kept so the next caller gets the same
     * answer at once. Without it every request touching the environment's
     * schema would retry an unreachable server — a pool that cannot start
     * takes seconds to say so (sqlx-jdf). Cleared by {@link #refreshSchema()}
     * and {@link #forgetSchemaFailure()}, which is what a load or pull does.
     */
    private RuntimeException schemaFailure;

    public DatabaseServer(String name, ConnectionInfo connectionInfo, List<SourceDefinition> sourceDefinitions,
                          SchemaRepositoryFactory schemaRepositoryFactory) {
        this(name, connectionInfo, sourceDefinitions, schemaRepositoryFactory, null);
    }

    /** @param snapshotKey the cluster this server belongs to, whose schema snapshot it shares */
    public DatabaseServer(String name, ConnectionInfo connectionInfo, List<SourceDefinition> sourceDefinitions,
                          SchemaRepositoryFactory schemaRepositoryFactory, SchemaSnapshot.Key snapshotKey) {
        this.name = name;
        this.connectionInfo = connectionInfo;
        this.sourceDefinitions = List.copyOf(sourceDefinitions);
        this.schemaRepositoryFactory = schemaRepositoryFactory;
        this.snapshotKey = snapshotKey;
    }

    public String getName() {
        return name;
    }

    public ConnectionInfo getConnectionInfo() {
        return connectionInfo;
    }

    public List<SourceDefinition> getSourceDefinitions() {
        return sourceDefinitions;
    }

    public synchronized DataSource getDataSource() {
        if (dataSource == null) {
            HikariConfig config = new HikariConfig();
            config.setPoolName("metadata-" + name);
            config.setJdbcUrl(connectionInfo.jdbcUrl());
            config.setUsername(connectionInfo.username());
            config.setPassword(connectionInfo.password());
            // schema introspection is bursty: a handful of sequential bulk reads,
            // then mostly idle until a reload or an on-demand create statement.
            config.setMinimumIdle(0);
            config.setMaximumPoolSize(4);
            dataSource = new HikariDataSource(config);
        }
        return dataSource;
    }

    public synchronized SqlSchemaRepository getSchemaRepository() {
        return getSchemaLoad().schema();
    }

    /**
     * The schema and how it was obtained — from the cluster's snapshot when the
     * server's fingerprint still matches it, or read in full (sqlx-sqy.4).
     */
    public synchronized SchemaRepositoryFactory.SchemaLoad getSchemaLoad() {
        if (schemaFailure != null) {
            throw schemaFailure;
        }
        if (schemaLoad == null) {
            try {
                schemaLoad = schemaRepositoryFactory.loadCurrent(getDataSource(), snapshotKey, name);
            } catch (RuntimeException e) {
                // Logged here, once, rather than by every caller that meets the
                // remembered failure afterwards.
                log.warn("Schema of server {} could not be read: {}", name, e.toString());
                schemaFailure = e;
                throw e;
            }
        }
        return schemaLoad;
    }

    /**
     * The cluster's last snapshot, with no connection to this server — for
     * when no server of the cluster can be reached. Not current.
     */
    public java.util.Optional<SchemaRepositoryFactory.SchemaLoad> lastKnownSchema() {
        return schemaRepositoryFactory.lastKnown(snapshotKey);
    }

    /**
     * Before a load or pull: keep the schema in memory if the server's
     * fingerprint still matches it, otherwise drop it so the next access reads
     * it again — from the snapshot or the server, whichever is current. A
     * failure remembered from last time is forgotten either way, so a server
     * that has come back is tried.
     */
    public synchronized void revalidateSchema() {
        schemaFailure = null;
        if (schemaLoad == null) {
            return;
        }
        boolean current;
        try {
            current = schemaRepositoryFactory.stillCurrent(getDataSource(), schemaLoad.fingerprint());
        } catch (RuntimeException e) {
            current = false;    // a pool that cannot start cannot vouch for anything
        }
        if (!current) {
            schemaLoad = null;
        }
    }

    /** Let the next schema access try this server again, after a failed load. */
    public synchronized void forgetSchemaFailure() {
        schemaFailure = null;
    }

    /** Whether the schema has been loaded, without triggering a load. */
    public synchronized boolean isSchemaLoaded() {
        return schemaLoad != null;
    }

    /** Drop the cached schema so the next access reloads it from the server (the pool is kept). */
    public synchronized void refreshSchema() {
        schemaLoad = null;
        schemaFailure = null;
    }

    @Override
    public synchronized void close() {
        schemaLoad = null;
        schemaFailure = null;
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }
}
