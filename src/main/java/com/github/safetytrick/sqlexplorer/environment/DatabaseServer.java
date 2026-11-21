package com.github.safetytrick.sqlexplorer.environment;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import com.github.safetytrick.sqlexplorer.schema.SchemaSnapshot;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DatabaseServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DatabaseServer.class);

    private final String name;
    private final ConnectionInfo connectionInfo;
    private final List<SourceDefinition> sourceDefinitions;
    private final SchemaRepositoryFactory schemaRepositoryFactory;
    private final SchemaSnapshot.Key snapshotKey;
    private final Set<ServerRole> roles;

    private final Object poolLock = new Object();
    private final ReentrantLock schemaLock = new ReentrantLock();
    private volatile Thread puller;

    private volatile HikariDataSource dataSource;
    private volatile SchemaRepositoryFactory.SchemaLoad schemaLoad;
    private volatile RuntimeException schemaFailure;
    private volatile java.util.Optional<SchemaRepositoryFactory.SchemaLoad> lastKnown;

    private volatile String offlineIn;

    public DatabaseServer(String name, ConnectionInfo connectionInfo, List<SourceDefinition> sourceDefinitions,
                          SchemaRepositoryFactory schemaRepositoryFactory) {
        this(name, connectionInfo, sourceDefinitions, schemaRepositoryFactory, null);
    }

    public DatabaseServer(String name, ConnectionInfo connectionInfo, List<SourceDefinition> sourceDefinitions,
                          SchemaRepositoryFactory schemaRepositoryFactory, SchemaSnapshot.Key snapshotKey) {
        this(name, connectionInfo, sourceDefinitions, schemaRepositoryFactory, snapshotKey, Set.of());
    }

    public DatabaseServer(String name, ConnectionInfo connectionInfo, List<SourceDefinition> sourceDefinitions,
                          SchemaRepositoryFactory schemaRepositoryFactory, SchemaSnapshot.Key snapshotKey,
                          Set<ServerRole> roles) {
        this.roles = roles == null || roles.isEmpty() ? Set.of() : java.util.Collections.unmodifiableSet(
            java.util.EnumSet.copyOf(roles));
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

    public Set<ServerRole> getRoles() {
        return roles;
    }

    public boolean hasRole(ServerRole role) {
        return roles.contains(role);
    }

    public void setOffline(String environment) {
        this.offlineIn = environment;
    }

    public boolean isOffline() {
        return offlineIn != null;
    }

    public void setPuller(Thread thread) {
        this.puller = thread;
    }

    public DataSource getDataSource() {
        String offline = offlineIn;
        if (offline != null) {
            throw new ServerOfflineException(offline, name);
        }
        HikariDataSource existing = dataSource;
        if (existing != null) {
            return existing;
        }
        synchronized (poolLock) {
            if (dataSource == null) {
                HikariConfig config = new HikariConfig();
                config.setPoolName("metadata-" + name);
                config.setJdbcUrl(connectionInfo.jdbcUrl());
                config.setUsername(connectionInfo.username());
                config.setPassword(connectionInfo.password());
                config.setMinimumIdle(0);
                config.setMaximumPoolSize(4);
                dataSource = new HikariDataSource(config);
            }
            return dataSource;
        }
    }

    public SqlSchemaRepository getSchemaRepository() {
        return getSchemaLoad().schema();
    }

    public SchemaRepositoryFactory.SchemaLoad getSchemaLoad() {
        SchemaRepositoryFactory.SchemaLoad loaded = schemaLoad;
        if (loaded != null) {
            return loaded;
        }
        RuntimeException failure = schemaFailure;
        if (failure != null) {
            throw failure;
        }
        String offline = offlineIn;
        if (offline != null) {
            throw new ServerOfflineException(offline, name);
        }
        Thread pulling = puller;
        boolean isPull = pulling == Thread.currentThread();
        if (pulling != null && !isPull) {
            throw new SchemaPendingException(name, "its environment is being pulled");
        }
        if (isPull) {
            schemaLock.lock();
        } else if (!schemaLock.tryLock()) {
            throw new SchemaPendingException(name, "it is being read");
        }
        try {
            loaded = schemaLoad;
            if (loaded != null) {
                return loaded;
            }
            failure = schemaFailure;
            if (failure != null) {
                throw failure;
            }
            try {
                loaded = schemaRepositoryFactory.loadCurrent(getDataSource(), snapshotKey, name);
                schemaLoad = loaded;
                return loaded;
            } catch (SchemaPendingException | ServerOfflineException e) {
                throw e;
            } catch (RuntimeException e) {
                log.warn("Schema of server {} could not be read: {}", name, e.toString());
                schemaFailure = e;
                throw e;
            }
        } finally {
            schemaLock.unlock();
        }
    }

    public java.util.Optional<SchemaRepositoryFactory.SchemaLoad> lastKnownSchema() {
        java.util.Optional<SchemaRepositoryFactory.SchemaLoad> known = lastKnown;
        if (known == null) {
            known = schemaRepositoryFactory.lastKnown(snapshotKey);
            lastKnown = known;
        }
        return known;
    }

    public void revalidateSchema() {
        schemaFailure = null;
        lastKnown = null;
        schemaLock.lock();
        try {
            SchemaRepositoryFactory.SchemaLoad loaded = schemaLoad;
            if (loaded == null || offlineIn != null) {
                return;
            }
            boolean current;
            try {
                current = schemaRepositoryFactory.stillCurrent(getDataSource(), loaded.fingerprint());
            } catch (RuntimeException e) {
                current = false;
            }
            if (!current) {
                schemaLoad = null;
            } else {
                try {
                    SchemaRepositoryFactory.SchemaLoad fresh =
                        schemaRepositoryFactory.withFreshStats(getDataSource(), loaded, snapshotKey, name);
                    if (fresh != null) {
                        schemaLoad = fresh;
                    }
                } catch (RuntimeException e) {
                    log.warn("Table statistics of server {} could not be refreshed: {}", name, e.toString());
                }
            }
        } finally {
            schemaLock.unlock();
        }
    }

    public void forgetSchemaFailure() {
        schemaFailure = null;
    }

    public boolean isSchemaLoaded() {
        return schemaLoad != null;
    }

    public void refreshSchema() {
        schemaLoad = null;
        schemaFailure = null;
        lastKnown = null;
    }

    @Override
    public void close() {
        schemaLoad = null;
        schemaFailure = null;
        lastKnown = null;
        synchronized (poolLock) {
            if (dataSource != null) {
                dataSource.close();
                dataSource = null;
            }
        }
    }
}
