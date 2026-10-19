package com.github.safetytrick.sqlexplorer.environment;

import java.util.ArrayList;
import java.util.List;

import com.github.safetytrick.sqlexplorer.schema.CompositeSchemaRepository;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;

/**
 * A named deployment context (staging, prod, ...) containing the database
 * servers whose SQL and schema are analyzed together.
 */
public final class Environment implements AutoCloseable {

    private final String name;
    private final List<Cluster> clusters;
    private final List<DatabaseServer> servers;

    private final boolean explainEnabled;
    private final String pullCron;

    public Environment(String name, List<DatabaseServer> servers) {
        this(name, servers, false, null);
    }

    public Environment(String name, List<DatabaseServer> servers, boolean explainEnabled) {
        this(name, servers, explainEnabled, null);
    }

    /** Each server its own cluster — the shape of a flat {@code servers:} block. */
    public Environment(String name, List<DatabaseServer> servers, boolean explainEnabled, String pullCron) {
        this(servers.stream().map(Cluster::of).toList(), name, explainEnabled, pullCron);
    }

    /** An environment of clusters: replicas grouped with the primary whose schema they carry. */
    public static Environment ofClusters(String name, List<Cluster> clusters, boolean explainEnabled,
                                         String pullCron) {
        return new Environment(clusters, name, explainEnabled, pullCron);
    }

    // clusters first only so its erasure differs from the public server-list constructor
    private Environment(List<Cluster> clusters, String name, boolean explainEnabled, String pullCron) {
        this.explainEnabled = explainEnabled;
        this.pullCron = pullCron;
        this.name = name;
        this.clusters = List.copyOf(clusters);
        this.servers = this.clusters.stream().flatMap(cluster -> cluster.servers().stream()).toList();
    }

    /** Whether EXPLAIN may be run against this environment's servers. Off by default. */
    public boolean isExplainEnabled() {
        return explainEnabled;
    }

    /** Cron expression to pull this environment's sources on, or null for none. */
    public String getPullCron() {
        return pullCron;
    }

    public String getName() {
        return name;
    }

    /** Every server of every cluster, in declaration order. */
    public List<DatabaseServer> getServers() {
        return servers;
    }

    public List<Cluster> getClusters() {
        return clusters;
    }

    /**
     * The schema of every cluster that can be read, each from the first of its
     * {@code schemaFrom} servers able to supply it.
     *
     * <p>Per cluster, not per server: a primary's replicas carry its schema, so
     * reading each of them again was work whose result was discarded — only
     * the first copy of a table was ever used (sqlx-sqy.2). Servers outside
     * {@code schemaFrom} are never read for schema at all.
     *
     * <p>A cluster whose schema cannot be read from any of its servers is left
     * out rather than failing the environment (sqlx-jdf); its absence is
     * reported by {@link #schemaStatus()} and by its sources. Only when no
     * cluster can be read is there nothing to return, and then the first
     * failure is the answer.
     */
    public SqlSchemaRepository schemaRepository() {
        List<SqlSchemaRepository> readable = new ArrayList<>(clusters.size());
        RuntimeException firstFailure = null;
        for (Cluster cluster : clusters) {
            try {
                readable.add(cluster.readSchema().schema());
            } catch (RuntimeException e) {
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }
        if (readable.isEmpty() && firstFailure != null) {
            throw firstFailure;
        }
        return readable.size() == 1 ? readable.getFirst() : new CompositeSchemaRepository(readable);
    }

    /** Where each cluster's schema came from, or why it could not be read. */
    public List<ClusterSchemaStatus> schemaStatus() {
        List<ClusterSchemaStatus> status = new ArrayList<>(clusters.size());
        for (Cluster cluster : clusters) {
            List<String> servers = cluster.servers().stream().map(DatabaseServer::getName).toList();
            try {
                Cluster.SchemaSource source = cluster.readSchema();
                status.add(new ClusterSchemaStatus(cluster.name(), servers, source.server(), source.fallback(),
                    source.passedOver(), null, source.current(), source.fromSnapshot(), source.readAt()));
            } catch (RuntimeException e) {
                status.add(new ClusterSchemaStatus(cluster.name(), servers, null, false, List.of(),
                    e.getMessage(), false, false, null));
            }
        }
        return List.copyOf(status);
    }

    /**
     * @param schemaServer the server that supplied the schema; null when none could
     * @param fallback     whether that server was not the cluster's first choice
     * @param passedOver   the servers tried before it, each with why it was passed over
     * @param failure      why no server could supply it; null when one did
     * @param current      whether a server confirmed the schema is the one it has now
     * @param fromSnapshot whether it came from the cluster's persisted snapshot
     * @param readAt       when the schema was read from a server; null when it never was
     */
    public record ClusterSchemaStatus(String cluster, List<String> servers, String schemaServer, boolean fallback,
                                      List<String> passedOver, String failure, boolean current,
                                      boolean fromSnapshot, java.time.Instant readAt) { }

    /** Before a load or pull: keep each server's schema only if its fingerprint still matches (sqlx-sqy.4). */
    public void revalidateSchemas() {
        servers.forEach(DatabaseServer::revalidateSchema);
    }

    /** Let every server whose schema failed to load be tried again on next use. */
    public void forgetSchemaFailures() {
        servers.forEach(DatabaseServer::forgetSchemaFailure);
    }

    /** Drop each server's cached schema so the next access reloads it. */
    public void refreshSchema() {
        servers.forEach(DatabaseServer::refreshSchema);
    }

    @Override
    public void close() {
        servers.forEach(DatabaseServer::close);
    }
}
