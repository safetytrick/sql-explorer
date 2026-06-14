package com.github.safetytrick.sqlexplorer.environment;

import javax.sql.DataSource;

import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * One MySQL server inside an {@link Environment}. Owns its connection pool and
 * the schema loaded from it; both are built lazily on first use so the
 * application can start without reaching any metadata database.
 */
public final class DatabaseServer implements AutoCloseable {

    private final String name;
    private final ConnectionInfo connectionInfo;
    private final SchemaRepositoryFactory schemaRepositoryFactory;

    private HikariDataSource dataSource;
    private SqlSchemaRepository schemaRepository;

    public DatabaseServer(String name, ConnectionInfo connectionInfo, SchemaRepositoryFactory schemaRepositoryFactory) {
        this.name = name;
        this.connectionInfo = connectionInfo;
        this.schemaRepositoryFactory = schemaRepositoryFactory;
    }

    public String getName() {
        return name;
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
        if (schemaRepository == null) {
            schemaRepository = schemaRepositoryFactory.load(getDataSource());
        }
        return schemaRepository;
    }

    @Override
    public synchronized void close() {
        schemaRepository = null;
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }
}
