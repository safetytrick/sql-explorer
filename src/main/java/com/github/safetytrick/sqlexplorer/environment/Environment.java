package com.github.safetytrick.sqlexplorer.environment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.github.safetytrick.sqlexplorer.schema.CompositeSchemaRepository;
import com.github.safetytrick.sqlexplorer.schema.SchemaFingerprint;
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

    /** Served from its cache, connecting to nothing, until something takes it live (sqlx-nbi). */
    private volatile boolean cached;

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

    /**
     * Whether the environment is served from its cache with every server
     * disconnected (sqlx-nbi). Set at startup when the cache is fresh; cleared
     * by any load or pull, which is what going live means.
     */
    public boolean isCached() {
        return cached;
    }

    public void setCached(boolean cached) {
        this.cached = cached;
        servers.forEach(server -> server.setOffline(cached ? name : null));
    }

    /** Cron expression to pull this environment's sources on, or null for none. */
    public String getPullCron() {
        return pullCron;
    }

    public String getName() {
        return name;
    }

    /**
     * THE GUARD for every query SQL Explorer would run against table data —
     * profiling, cardinality estimates, samples (sqlx-n5j.4). A feature that
     * samples goes through here and nowhere else, so the rules below hold for
     * all of them rather than for the ones that remembered to check:
     *
     * <ol>
     *   <li><b>Only a server with the {@code sampling} role.</b> Config opts a
     *       server in by name; absent that, nothing is sampled, and the
     *       exception says so. When several have it, the first declared.</li>
     *   <li><b>Never while the environment is served from its cache.</b> A
     *       cached environment connects to nothing (sqlx-nbi), and sampling is
     *       no exception: pull it first.</li>
     *   <li><b>Within {@link SamplingLimits}:</b> a read-only session, a
     *       {@code max_execution_time} on every statement — both applied by
     *       {@link SamplingTarget#openSession()} — and row caps by primary-key
     *       range, which the caller keeps.</li>
     * </ol>
     *
     * <p>It decides without connecting: the answer comes from config and the
     * environment's state alone.
     *
     * @throws SamplingUnavailableException naming why there is no target
     */
    public SamplingTarget samplingTarget() {
        DatabaseServer sampling = servers.stream()
            .filter(server -> server.hasRole(ServerRole.SAMPLING))
            .findFirst()
            .orElseThrow(() -> new SamplingUnavailableException("environment " + name
                + " has no server with the sampling role, so nothing in it may be sampled; "
                + "give a replica that serves no application traffic `roles: [sampling]` to allow it"));
        if (cached) {
            throw new SamplingUnavailableException("environment " + name + " is served from its cache, so "
                + "server " + sampling.getName() + " is not connected; pull the environment to go live");
        }
        return new SamplingTarget(name, sampling, SamplingLimits.DEFAULT);
    }

    /**
     * What {@link #samplingTarget()} would answer, without throwing — for
     * {@code /environments}.
     *
     * @param server    the sampling server; null when no server has the role
     * @param available whether a feature could sample now
     * @param reason    why it cannot; null when it can
     */
    public record SamplingStatus(String server, boolean available, String reason) { }

    public SamplingStatus samplingStatus() {
        String server = servers.stream()
            .filter(candidate -> candidate.hasRole(ServerRole.SAMPLING))
            .map(DatabaseServer::getName)
            .findFirst().orElse(null);
        try {
            samplingTarget();
            return new SamplingStatus(server, true, null);
        } catch (SamplingUnavailableException e) {
            return new SamplingStatus(server, false, e.getMessage());
        }
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

    /**
     * Whether the schema a source's rows would be analysed against was
     * confirmed current — the gate for analysing anything new (sqlx-sqy.5).
     *
     * <p>A source belongs to the cluster listing its server; that cluster's
     * schema must have come from a server that vouched for it, not from a
     * snapshot no reachable server confirmed. A source outside every cluster —
     * an upload, a file — has no cluster to wait on and is analysed against
     * whatever schema is loaded, as it always was.
     */
    public boolean isSchemaCurrentFor(String sourceRef) {
        for (Cluster cluster : clusters) {
            if (cluster.servers().stream().anyMatch(server -> server.getName().equals(sourceRef))) {
                try {
                    return cluster.readSchema().current();
                } catch (RuntimeException e) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Tables defined differently by clusters that carry the same schema —
     * every shard of a sharded application carries the same schema (sqlx-sqy.7).
     *
     * <p>Lookups still take the first cluster's definition (sqlx-3js); this is
     * what that choice hides. A migration applied to some shards and not others
     * shows up here as a table with more than one variant, instead of being
     * masked by whichever shard happened to be listed first.
     *
     * <p>Compared by fingerprint, so only clusters whose schema came with one
     * take part, and only schemas at least two of them carry. A table one
     * cluster lacks is a variant of its own.
     */
    public List<TableDrift> schemaDrift() {
        Map<String, SchemaFingerprint> byCluster = new LinkedHashMap<>();
        for (Cluster cluster : clusters) {
            try {
                SchemaFingerprint fingerprint = cluster.readSchema().fingerprint();
                if (fingerprint != null) {
                    byCluster.put(cluster.name(), fingerprint);
                }
            } catch (RuntimeException e) {
                // unreadable: reported by schemaStatus(), and has nothing to compare
            }
        }
        Set<String> schemaNames = new TreeSet<>();
        byCluster.values().forEach(fingerprint -> fingerprint.tables().keySet()
            .forEach(table -> schemaNames.add(table.substring(0, table.indexOf('.')))));

        List<TableDrift> drift = new ArrayList<>();
        for (String schemaName : schemaNames) {
            Map<String, SchemaFingerprint> carrying = new LinkedHashMap<>();
            byCluster.forEach((cluster, fingerprint) -> {
                SchemaFingerprint only = fingerprint.onlySchema(schemaName);
                if (!only.tables().isEmpty()) {
                    carrying.put(cluster, only);
                }
            });
            if (carrying.size() < 2) {
                continue;
            }
            Set<String> tables = new TreeSet<>();
            carrying.values().forEach(fingerprint -> tables.addAll(fingerprint.tables().keySet()));
            for (String table : tables) {
                // clusters grouped by the definition they have, in cluster order; null = absent
                Map<String, List<String>> variants = new LinkedHashMap<>();
                carrying.forEach((cluster, fingerprint) -> variants
                    .computeIfAbsent(String.valueOf(fingerprint.tables().get(table)), hash -> new ArrayList<>())
                    .add(cluster));
                if (variants.size() > 1) {
                    drift.add(new TableDrift(table, variants.entrySet().stream()
                        .map(variant -> new Variant(List.copyOf(variant.getValue()), !"null".equals(variant.getKey())))
                        .toList()));
                }
            }
        }
        return List.copyOf(drift);
    }

    /**
     * One table that clusters carrying its schema do not agree on.
     *
     * @param table    {@code schema.table}, lowercase
     * @param variants the clusters sharing each definition, in cluster order —
     *                 lookups use the first variant that is present
     */
    public record TableDrift(String table, List<Variant> variants) { }

    /** @param present false for the clusters that do not have the table at all */
    public record Variant(List<String> clusters, boolean present) { }

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

    /**
     * The thread pulling this environment, which alone loads its servers'
     * schemas until the pull ends; null when it has (sqlx-o7x). Meanwhile
     * every other caller is served the clusters' snapshots, as the
     * environment is served from its captures until the pull completes.
     */
    public void setPuller(Thread thread) {
        servers.forEach(server -> server.setPuller(thread));
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
