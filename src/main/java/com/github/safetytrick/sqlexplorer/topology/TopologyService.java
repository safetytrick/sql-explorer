package com.github.safetytrick.sqlexplorer.topology;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.environment.DatabaseServer;
import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import com.github.safetytrick.sqlexplorer.environment.ServerRole;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepository;
import com.github.safetytrick.sqlexplorer.evidence.SourceScope;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryManager;
import com.github.safetytrick.sqlexplorer.model.QueryKind;
import com.github.safetytrick.sqlexplorer.topology.Topology.Load;
import com.github.safetytrick.sqlexplorer.topology.Topology.Share;
import com.github.safetytrick.sqlexplorer.topology.Topology.Status;

@Service
public class TopologyService {

    private static final BigDecimal PICOS_PER_SECOND = BigDecimal.valueOf(1_000_000_000_000L);

    private final SqlRepositoryManager repositories;
    private final EnvironmentRegistry environments;

    public TopologyService(SqlRepositoryManager repositories, EnvironmentRegistry environments) {
        this.repositories = repositories;
        this.environments = environments;
    }

    static double seconds(RuntimeMetrics metrics) {
        BigDecimal picos = metrics == null ? null : metrics.getTotalTime();
        return picos == null ? 0 : picos.divide(PICOS_PER_SECOND, java.math.MathContext.DECIMAL64).doubleValue();
    }

    public Topology.Report report(String environmentName) {
        Environment environment = environments.get(environmentName);
        boolean loaded = repositories.getLoadState(environmentName) != null;
        Set<String> failed = new HashSet<>();
        Map<String, String> failures = new LinkedHashMap<>();
        if (loaded) {
            repositories.getFailedSources(environmentName).forEach(f -> {
                failed.add(f.sourceRef());
                failures.put(f.sourceRef(), f.reason());
            });
        }

        Map<String, Tally> tallies = loaded ? talliesBySource(environmentName) : Map.of();
        double environmentSeconds = tallies.values().stream().mapToDouble(t -> t.load().seconds()).sum();

        List<Topology.Cluster> clusters = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        Tally total = new Tally();
        tallies.values().forEach(total::add);
        if (environment != null) {
            for (com.github.safetytrick.sqlexplorer.environment.Cluster cluster : environment.getClusters()) {
                List<Topology.Server> servers = new ArrayList<>();
                Tally clusterTally = new Tally();
                for (DatabaseServer server : ordered(cluster.servers())) {
                    placed.add(server.getName());
                    Tally tally = tallies.get(server.getName());
                    if (tally != null) {
                        clusterTally.add(tally);
                    }
                    servers.add(server(server.getName(), roles(server),
                        cluster.schemaFrom().contains(server), loaded, !server.getSourceDefinitions().isEmpty(),
                        tally, failed.contains(server.getName()), failures.get(server.getName()),
                        environmentSeconds));
                }
                clusters.add(new Topology.Cluster(cluster.name(), servers, clusterTally.load()));
            }
        }
        List<Topology.Server> others = new ArrayList<>();
        tallies.forEach((ref, tally) -> {
            if (!placed.contains(ref)) {
                others.add(server(ref, List.of(), false, true, true, tally, failed.contains(ref), failures.get(ref),
                    environmentSeconds));
            }
        });
        failures.forEach((ref, reason) -> {
            if (!placed.contains(ref) && !tallies.containsKey(ref)) {
                others.add(server(ref, List.of(), false, true, true, null, true, reason, environmentSeconds));
            }
        });
        return new Topology.Report(environmentName, loaded, environment != null && environment.isCached(),
            clusters, others, total.load(), BASIS);
    }

    static final String BASIS = "Each server's own Performance Schema, summed where the statements ran: a statement "
        + "run on the primary and on a replica counts on both. Counters run from each server's last reset, so "
        + "windows differ; compare seconds per day rather than totals. A statement's kind is what it does, read past "
        + "any CTEs: SELECT reads; INSERT, UPDATE, DELETE and REPLACE write; anything else (SET, COMMIT, CALL, "
        + "DDL) is other. Unknown is a statement whose operation could not be read, usually because its text was "
        + "truncated; it counts as a write if it changed any rows. Statements a full digest table discarded are not counted; see Captured.";

    private Map<String, Tally> talliesBySource(String environmentName) {
        Map<String, Tally> tallies = new LinkedHashMap<>();
        for (SqlRepository repository : repositories.getRepositories(environmentName)) {
            String ref = repository.sourceRef();
            if (ref == null || tallies.containsKey(ref)) {
                continue;
            }
            Tally tally = new Tally();
            repositories.getSqlRepository(environmentName, SourceScope.only(ref)).getAllAnalyzedSql()
                .forEach(tally::add);
            tallies.put(ref, tally);
        }
        return tallies;
    }

    static List<DatabaseServer> ordered(List<DatabaseServer> servers) {
        return servers.stream().sorted(Comparator.comparingInt(TopologyService::rank)).toList();
    }

    private static int rank(DatabaseServer server) {
        if (server.hasRole(ServerRole.PRIMARY)) {
            return 0;
        }
        if (server.hasRole(ServerRole.REPLICA)) {
            return 1;
        }
        return server.hasRole(ServerRole.ANALYTICS) ? 2 : 3;
    }

    static List<String> roles(DatabaseServer server) {
        return server.getRoles().stream().map(ServerRole::configName).toList();
    }

    private static Topology.Server server(String name, List<String> roles, boolean schemaSource, boolean loaded,
                                          boolean hasSource, Tally tally, boolean failed, String reason,
                                          double environmentSeconds) {
        Status status;
        String why = null;
        if (!loaded) {
            status = Status.NOT_LOADED;
        } else if (tally != null) {
            status = failed ? Status.STALE : Status.READ;
            if (failed) {
                why = "the latest load could not read it; these figures are its previous capture. " + reason;
            }
        } else if (failed) {
            status = Status.UNREACHABLE;
            why = reason;
        } else if (!hasSource) {
            status = Status.NO_SOURCE;
            why = "config gives this server no statement source";
        } else {
            status = Status.UNREACHABLE;
            why = "nothing was loaded from it";
        }
        Load load = tally == null ? null : tally.load();
        Double share = load == null || environmentSeconds <= 0 ? null : load.seconds() / environmentSeconds;
        return new Topology.Server(name, roles, schemaSource, status, load, tally == null ? null : tally.window(),
            share, why);
    }

    static final class Tally {
        private final Map<QueryKind, long[]> counts = new EnumMap<>(QueryKind.class);
        private final Map<QueryKind, double[]> seconds = new EnumMap<>(QueryKind.class);
        private LocalDateTime from;
        private LocalDateTime to;
        private Double perDay;
        private boolean single = true;

        Tally() {
            for (QueryKind kind : QueryKind.values()) {
                counts.put(kind, new long[2]);
                seconds.put(kind, new double[1]);
            }
        }

        void add(SqlProfile profile) {
            RuntimeMetrics metrics = profile.getRuntimeMetrics();
            QueryKind kind = QueryKind.of(profile);
            counts.get(kind)[0]++;
            if (metrics == null) {
                return;
            }
            counts.get(kind)[1] += metrics.getExecuted();
            seconds.get(kind)[0] += seconds(metrics);
            if (metrics.getFirstSeen() != null && (from == null || metrics.getFirstSeen().isBefore(from))) {
                from = metrics.getFirstSeen();
            }
            if (metrics.getLastSeen() != null && (to == null || metrics.getLastSeen().isAfter(to))) {
                to = metrics.getLastSeen();
            }
        }

        void add(Tally other) {
            for (QueryKind kind : QueryKind.values()) {
                counts.get(kind)[0] += other.counts.get(kind)[0];
                counts.get(kind)[1] += other.counts.get(kind)[1];
                seconds.get(kind)[0] += other.seconds.get(kind)[0];
            }
            Double rate = other.load().secondsPerDay();
            if (rate != null) {
                perDay = (perDay == null ? 0 : perDay) + rate;
            }
            single = false;
        }

        Topology.Window window() {
            if (!single || from == null || to == null || to.isBefore(from)) {
                return null;
            }
            return new Topology.Window(from, to, Duration.between(from, to).toSeconds() / 86_400d);
        }

        Load load() {
            List<Share> shares = new ArrayList<>();
            long statements = 0;
            long executions = 0;
            double total = 0;
            for (QueryKind kind : QueryKind.values()) {
                long[] c = counts.get(kind);
                double s = seconds.get(kind)[0];
                shares.add(new Share(kind, c[0], c[1], s));
                statements += c[0];
                executions += c[1];
                total += s;
            }
            Double rate = perDay;
            if (single) {
                Topology.Window window = window();
                rate = window == null ? null : total / Math.max(1d, window.days());
            }
            return new Load(statements, executions, total, rate, List.copyOf(shares));
        }
    }
}
