package com.github.safetytrick.sqlexplorer.environment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.schema.CompositeSchemaRepository;
import com.github.safetytrick.sqlexplorer.schema.SchemaFingerprint;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import com.github.safetytrick.sqlexplorer.schema.TableDiff;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;

public final class Environment implements AutoCloseable {

    private final String name;
    private final List<Cluster> clusters;
    private final List<DatabaseServer> servers;

    private final boolean explainEnabled;
    private final String pullCron;

    private volatile boolean cached;

    public Environment(String name, List<DatabaseServer> servers) {
        this(name, servers, false, null);
    }

    public Environment(String name, List<DatabaseServer> servers, boolean explainEnabled) {
        this(name, servers, explainEnabled, null);
    }

    public Environment(String name, List<DatabaseServer> servers, boolean explainEnabled, String pullCron) {
        this(servers.stream().map(Cluster::of).toList(), name, explainEnabled, pullCron);
    }

    public static Environment ofClusters(String name, List<Cluster> clusters, boolean explainEnabled,
                                         String pullCron) {
        return new Environment(clusters, name, explainEnabled, pullCron);
    }

    private Environment(List<Cluster> clusters, String name, boolean explainEnabled, String pullCron) {
        this.explainEnabled = explainEnabled;
        this.pullCron = pullCron;
        this.name = name;
        this.clusters = List.copyOf(clusters);
        this.servers = this.clusters.stream().flatMap(cluster -> cluster.servers().stream()).toList();
    }

    public boolean isExplainEnabled() {
        return explainEnabled;
    }

    public boolean isCached() {
        return cached;
    }

    public void setCached(boolean cached) {
        this.cached = cached;
        servers.forEach(server -> server.setOffline(cached ? name : null));
    }

    public String getPullCron() {
        return pullCron;
    }

    public String getName() {
        return name;
    }

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

    public record ClusterSampling(Cluster cluster, SamplingTarget target, String reason) { }

    public List<ClusterSampling> samplingByCluster(SamplingLimits limits) {
        return clusters.stream().map(cluster -> {
            DatabaseServer sampling = cluster.servers().stream()
                .filter(server -> server.hasRole(ServerRole.SAMPLING))
                .findFirst().orElse(null);
            if (sampling == null) {
                return new ClusterSampling(cluster, null, "cluster " + cluster.name()
                    + " has no server with the sampling role");
            }
            if (cached) {
                return new ClusterSampling(cluster, null, "environment " + name + " is served from its cache, "
                    + "so server " + sampling.getName() + " is not connected; pull the environment to go live");
            }
            return new ClusterSampling(cluster, new SamplingTarget(name, sampling, limits), null);
        }).toList();
    }

    public SamplingTarget samplingTargetOn(String serverName, SamplingLimits limits) {
        DatabaseServer server = servers.stream().filter(s -> s.getName().equals(serverName)).findFirst()
            .orElseThrow(() -> new SamplingUnavailableException("environment " + name + " has no server "
                + serverName));
        if (!server.hasRole(ServerRole.SAMPLING)) {
            throw new SamplingUnavailableException("server " + serverName + " does not have the sampling role, "
                + "so nothing on it may be sampled");
        }
        if (cached) {
            throw new SamplingUnavailableException("environment " + name + " is served from its cache, so server "
                + serverName + " is not connected; pull the environment to go live");
        }
        return new SamplingTarget(name, server, limits);
    }

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

    public List<DatabaseServer> getServers() {
        return servers;
    }

    public List<Cluster> getClusters() {
        return clusters;
    }

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

    public List<TableDrift> schemaDrift() {
        Map<String, SchemaFingerprint> byCluster = new LinkedHashMap<>();
        Map<String, SqlSchemaRepository> schemas = new LinkedHashMap<>();
        for (Cluster cluster : clusters) {
            try {
                Cluster.SchemaSource source = cluster.readSchema();
                if (source.fingerprint() != null) {
                    byCluster.put(cluster.name(), source.fingerprint());
                    schemas.put(cluster.name(), source.schema());
                }
            } catch (RuntimeException e) {
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
                Map<String, List<String>> variants = new LinkedHashMap<>();
                carrying.forEach((cluster, fingerprint) -> variants
                    .computeIfAbsent(String.valueOf(fingerprint.tables().get(table)), hash -> new ArrayList<>())
                    .add(cluster));
                if (variants.size() > 1) {
                    drift.add(new TableDrift(table, describe(table, variants, schemas)));
                }
            }
        }
        return List.copyOf(drift);
    }

    private static List<Variant> describe(String table, Map<String, List<String>> variants,
                                          Map<String, SqlSchemaRepository> schemas) {
        TableReference reference = TableReference.newTableReference(
            table.substring(0, table.indexOf('.')), table.substring(table.indexOf('.') + 1));
        List<Variant> described = new ArrayList<>(variants.size());
        TableStructure used = null;
        boolean usedSeen = false;
        for (Map.Entry<String, List<String>> variant : variants.entrySet()) {
            List<String> clusters = List.copyOf(variant.getValue());
            if ("null".equals(variant.getKey())) {
                described.add(new Variant(clusters, false));
                continue;
            }
            TableStructure structure = structureOf(schemas.get(clusters.getFirst()), reference);
            if (!usedSeen) {
                usedSeen = true;
                used = structure;
                described.add(new Variant(clusters, true));
            } else {
                described.add(new Variant(clusters, true,
                    used == null || structure == null ? null : TableDiff.between(used, structure)));
            }
        }
        return described;
    }

    private static TableStructure structureOf(SqlSchemaRepository schema, TableReference table) {
        try {
            return schema == null ? null : schema.getTable(table);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public record TableDrift(String table, List<Variant> variants) { }

    public record Variant(List<String> clusters, boolean present, List<TableDiff.Difference> differences) {
        public Variant(List<String> clusters, boolean present) {
            this(clusters, present, null);
        }
    }

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

    public record ClusterSchemaStatus(String cluster, List<String> servers, String schemaServer, boolean fallback,
                                      List<String> passedOver, String failure, boolean current,
                                      boolean fromSnapshot, java.time.Instant readAt) { }

    public void revalidateSchemas() {
        servers.forEach(DatabaseServer::revalidateSchema);
    }

    public void forgetSchemaFailures() {
        servers.forEach(DatabaseServer::forgetSchemaFailure);
    }

    public void setPuller(Thread thread) {
        servers.forEach(server -> server.setPuller(thread));
    }

    public void refreshSchema() {
        servers.forEach(DatabaseServer::refreshSchema);
    }

    @Override
    public void close() {
        servers.forEach(DatabaseServer::close);
    }
}
