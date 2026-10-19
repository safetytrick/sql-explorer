package com.github.safetytrick.sqlexplorer.environment;

import javax.sql.DataSource;
import java.util.List;

import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
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

    private HikariDataSource dataSource;
    private SqlSchemaRepository schemaRepository;
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
        this.name = name;
        this.connectionInfo = connectionInfo;
        this.sourceDefinitions = List.copyOf(sourceDefinitions);
        this.schemaRepositoryFactory = schemaRepositoryFactory;
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
        if (schemaFailure != null) {
            throw schemaFailure;
        }
        if (schemaRepository == null) {
            try {
                schemaRepository = schemaRepositoryFactory.load(getDataSource());
            } catch (RuntimeException e) {
                // Logged here, once, rather than by every caller that meets the
                // remembered failure afterwards.
                log.warn("Schema of server {} could not be read: {}", name, e.toString());
                schemaFailure = e;
                throw e;
            }
        }
        return schemaRepository;
    }

    /** Let the next schema access try this server again, after a failed load. */
    public synchronized void forgetSchemaFailure() {
        schemaFailure = null;
    }

    /** Whether the schema has been loaded, without triggering a load. */
    public synchronized boolean isSchemaLoaded() {
        return schemaRepository != null;
    }

    /** Drop the cached schema so the next access reloads it from the server (the pool is kept). */
    public synchronized void refreshSchema() {
        schemaRepository = null;
        schemaFailure = null;
    }

    @Override
    public synchronized void close() {
        schemaRepository = null;
        schemaFailure = null;
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }
}
