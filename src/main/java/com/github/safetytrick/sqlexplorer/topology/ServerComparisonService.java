package com.github.safetytrick.sqlexplorer.topology;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.environment.Cluster;
import com.github.safetytrick.sqlexplorer.environment.DatabaseServer;
import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import com.github.safetytrick.sqlexplorer.environment.ServerRole;
import com.github.safetytrick.sqlexplorer.evidence.SourceScope;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepository;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryManager;
import com.github.safetytrick.sqlexplorer.model.QueryKind;
import com.github.safetytrick.sqlexplorer.topology.ServerComparison.By;
import com.github.safetytrick.sqlexplorer.topology.ServerComparison.Cell;
import com.github.safetytrick.sqlexplorer.topology.ServerComparison.Column;
import com.github.safetytrick.sqlexplorer.topology.ServerComparison.Flag;
import com.github.safetytrick.sqlexplorer.topology.ServerComparison.Row;
import com.github.safetytrick.sqlexplorer.topology.ServerComparison.Sort;

@Service
public class ServerComparisonService {

    static final long MIN_EXECUTIONS = 10;
    static final double PLAN_FACTOR = 10;
    static final double PLAN_MIN_ROWS = 100;
    static final double SAME_ROWS_FACTOR = 2;
    static final double LATENCY_FACTOR = 3;
    static final double LATENCY_MIN_MS = 1;
    static final double SKEW_FACTOR = 3;
    static final long SKEW_MIN_EXECUTIONS = 20;

    static final int DEFAULT_LIMIT = 200;
    static final int MAX_LIMIT = 1000;
    private static final int TEXT_LENGTH = 300;
    private static final double PICOS_PER_MILLI = 1e9;

    static final String BASIS = "The queries the search matched, each set against its own figures on every server "
        + "of the cluster. Per-day figures divide a server's own totals by its own window, since each server's "
        + "counters run from its own last reset. Rows without a flag are hidden unless asked for. Replicated writes "
        + "show on a replica only under statement-based replication, and transactions are not visible, so a read "
        + "on the primary may be one that has to stay there.";

    private final SqlRepositoryManager repositories;
    private final EnvironmentRegistry environments;

    public ServerComparisonService(SqlRepositoryManager repositories, EnvironmentRegistry environments) {
        this.repositories = repositories;
        this.environments = environments;
    }

    public record Options(String cluster, By by, Set<Flag> flags, boolean all, Sort sort, Integer limit) { }

    public ServerComparison.Report compare(String environmentName, List<SqlProfile> matched, Options options) {
        Environment environment = environments.get(environmentName);
        List<Cluster> clusters = environment == null ? List.of() : environment.getClusters();
        Set<String> loaded = loadedSources(environmentName);
        Map<String, ServerCorpus> corpora = new HashMap<>();

        Cluster cluster = chooseCluster(environmentName, clusters, options.cluster(), loaded, corpora);
        By by = options.by() == null ? By.QUERY : options.by();
        List<String> clusterNames = clusters.stream().map(Cluster::name).toList();
        if (cluster == null) {
            return new ServerComparison.Report(environmentName, clusterNames, null, List.of(), by, matched.size(), 0,
                new EnumMap<>(Flag.class), 0, 0, List.of(), BASIS);
        }

        List<DatabaseServer> servers = TopologyService.ordered(cluster.servers());
        List<ServerCorpus> columns = servers.stream()
            .map(server -> loaded.contains(server.getName())
                ? corpora.computeIfAbsent(server.getName(), name -> corpus(environmentName, name))
                : null)
            .toList();
        List<Column> header = new ArrayList<>();
        for (int i = 0; i < servers.size(); i++) {
            DatabaseServer server = servers.get(i);
            ServerCorpus corpus = columns.get(i);
            header.add(new Column(server.getName(), TopologyService.roles(server), corpus != null,
                corpus == null ? null : corpus.windowDays()));
        }

        List<Row> queryRows = new ArrayList<>();
        Map<String, List<Row>> byTable = new LinkedHashMap<>();
        for (SqlProfile profile : matched) {
            String key = key(profile);
            List<Cell> cells = new ArrayList<>();
            boolean any = false;
            for (ServerCorpus corpus : columns) {
                SqlProfile there = corpus == null ? null : corpus.byKey().get(key);
                Cell cell = there == null ? null : cell(there.getRuntimeMetrics(), corpus.windowDays());
                any |= cell != null;
                cells.add(cell);
            }
            if (!any) {
                continue;
            }
            QueryKind kind = QueryKind.of(profile);
            Map<Flag, Double> flags = flags(kind, servers, columns, cells);
            Row row = new Row(profile.getDigest() != null ? profile.getDigest() : profile.getId(),
                text(profile.getQuery()), kind.name(), List.copyOf(flags.keySet()),
                flags.values().stream().mapToDouble(Double::doubleValue).max().orElse(0),
                perDay(cells), cells, 1);
            queryRows.add(row);
            if (by == By.TABLE) {
                tables(profile).forEach(table -> byTable.computeIfAbsent(table, t -> new ArrayList<>()).add(row));
            }
        }

        List<Row> rows = by == By.TABLE
            ? byTable.entrySet().stream().map(entry -> tableRow(entry.getKey(), entry.getValue(), columns)).toList()
            : queryRows;

        Map<Flag, Integer> flagCounts = new EnumMap<>(Flag.class);
        int unflagged = 0;
        for (Row row : rows) {
            row.flags().forEach(flag -> flagCounts.merge(flag, 1, Integer::sum));
            if (row.flags().isEmpty()) {
                unflagged++;
            }
        }

        Set<Flag> wanted = options.flags() == null ? Set.of() : options.flags();
        List<Row> shown = rows.stream()
            .filter(row -> wanted.isEmpty()
                ? options.all() || !row.flags().isEmpty()
                : row.flags().stream().anyMatch(wanted::contains))
            .sorted(comparator(options.sort()))
            .toList();
        int limit = options.limit() == null || options.limit() < 1 ? DEFAULT_LIMIT
            : Math.min(options.limit(), MAX_LIMIT);
        return new ServerComparison.Report(environmentName, clusterNames, cluster.name(), header, by,
            matched.size(), rows.size(), flagCounts, unflagged, shown.size(),
            shown.subList(0, Math.min(limit, shown.size())), BASIS);
    }

    static Map<Flag, Double> flags(QueryKind kind, List<DatabaseServer> servers, List<ServerCorpus> columns,
                                   List<Cell> cells) {
        Map<Flag, Double> flags = new EnumMap<>(Flag.class);
        List<Integer> replicas = new ArrayList<>();
        List<Integer> secondaries = new ArrayList<>();
        for (int i = 0; i < servers.size(); i++) {
            if (columns.get(i) == null) {
                continue;
            }
            if (servers.get(i).hasRole(ServerRole.REPLICA) && !servers.get(i).hasRole(ServerRole.PRIMARY)) {
                replicas.add(i);
            }
            if (!servers.get(i).hasRole(ServerRole.PRIMARY)) {
                secondaries.add(i);
            }
        }

        if (kind.isRead() && !replicas.isEmpty()) {
            double onPrimary = 0;
            for (int i = 0; i < servers.size(); i++) {
                if (servers.get(i).hasRole(ServerRole.PRIMARY) && cells.get(i) != null) {
                    onPrimary += cells.get(i).secondsPerDay();
                }
            }
            if (onPrimary > 0) {
                flags.put(Flag.ON_PRIMARY, onPrimary);
            }
        }

        if (kind.isRead() && replicas.size() >= 2) {
            long executions = 0;
            double max = 0;
            double min = Double.MAX_VALUE;
            double seconds = 0;
            for (int i : replicas) {
                Cell cell = cells.get(i);
                double rate = cell == null ? 0 : cell.executionsPerDay();
                executions += cell == null ? 0 : cell.executions();
                seconds += cell == null ? 0 : cell.secondsPerDay();
                max = Math.max(max, rate);
                min = Math.min(min, rate);
            }
            if (executions >= SKEW_MIN_EXECUTIONS && max > 0 && max >= SKEW_FACTOR * min) {
                double even = seconds / replicas.size();
                double excess = 0;
                for (int i : replicas) {
                    Cell cell = cells.get(i);
                    excess += Math.max(0, (cell == null ? 0 : cell.secondsPerDay()) - even);
                }
                flags.put(Flag.SKEWED, excess);
            }
        }

        if (kind.isRead() && secondaries.size() >= 2) {
            List<Integer> ranOn = secondaries.stream().filter(i -> cells.get(i) != null).toList();
            boolean onPrimary = false;
            for (int i = 0; i < servers.size(); i++) {
                onPrimary |= servers.get(i).hasRole(ServerRole.PRIMARY) && cells.get(i) != null;
            }
            if (ranOn.size() == 1 && !onPrimary) {
                flags.put(Flag.SINGLE_SERVER, cells.get(ranOn.getFirst()).secondsPerDay());
            }
        }

        List<Cell> steady = cells.stream()
            .filter(cell -> cell != null && cell.executions() >= MIN_EXECUTIONS).toList();
        if (steady.size() >= 2) {
            double minRows = steady.stream().mapToDouble(Cell::rowsExaminedPerExecution).min().orElse(0);
            double maxRows = steady.stream().mapToDouble(Cell::rowsExaminedPerExecution).max().orElse(0);
            if (maxRows >= PLAN_MIN_ROWS && maxRows >= PLAN_FACTOR * Math.max(minRows, 1)) {
                flags.put(Flag.PLAN_DIFFERS, steady.stream()
                    .mapToDouble(cell -> cell.secondsPerDay() * (1 - Math.max(minRows, 1)
                        / Math.max(cell.rowsExaminedPerExecution(), 1)))
                    .sum());
            } else if (maxRows <= SAME_ROWS_FACTOR * Math.max(minRows, 1)) {
                double minMs = steady.stream().mapToDouble(Cell::msPerExecution).min().orElse(0);
                double maxMs = steady.stream().mapToDouble(Cell::msPerExecution).max().orElse(0);
                if (maxMs >= LATENCY_MIN_MS && maxMs >= LATENCY_FACTOR * minMs) {
                    flags.put(Flag.LATENCY_DIFFERS, steady.stream()
                        .mapToDouble(cell -> cell.msPerExecution() <= 0 ? 0
                            : cell.secondsPerDay() * (1 - minMs / cell.msPerExecution()))
                        .sum());
                }
            }
        }

        double replicaWrites = 0;
        boolean wrote = false;
        for (int i = 0; i < servers.size(); i++) {
            Cell cell = cells.get(i);
            DatabaseServer server = servers.get(i);
            if (cell == null || server.hasRole(ServerRole.PRIMARY)
                || !(server.hasRole(ServerRole.REPLICA) || server.hasRole(ServerRole.ANALYTICS))) {
                continue;
            }
            if (cell.rowsAffected() > 0 || (kind.isWrite() && cell.executions() > 0)) {
                wrote = true;
                replicaWrites += cell.secondsPerDay();
            }
        }
        if (wrote) {
            flags.put(Flag.REPLICA_WRITES, replicaWrites);
        }
        return flags;
    }

    private static Row tableRow(String table, List<Row> queries, List<ServerCorpus> columns) {
        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            long executions = 0;
            long rowsAffected = 0;
            double seconds = 0;
            double perDay = 0;
            double executionsPerDay = 0;
            double rowsExamined = 0;
            boolean any = false;
            for (Row query : queries) {
                Cell cell = query.cells().get(i);
                if (cell == null) {
                    continue;
                }
                any = true;
                executions += cell.executions();
                rowsAffected += cell.rowsAffected();
                seconds += cell.seconds();
                perDay += cell.secondsPerDay();
                executionsPerDay += cell.executionsPerDay();
                rowsExamined += cell.rowsExaminedPerExecution() * cell.executions();
            }
            cells.add(any ? new Cell(executions, seconds, perDay, executionsPerDay,
                executions == 0 ? 0 : rowsExamined / executions,
                executions == 0 ? 0 : seconds * 1000 / executions, rowsAffected) : null);
        }
        Set<Flag> flags = new LinkedHashSet<>();
        queries.forEach(query -> flags.addAll(query.flags()));
        List<Flag> ordered = flags.stream().sorted().toList();
        return new Row(table, table, null, ordered, queries.stream().mapToDouble(Row::difference).sum(),
            perDay(cells), cells, queries.size());
    }

    private static Comparator<Row> comparator(Sort sort) {
        Comparator<Row> byTime = Comparator.comparingDouble(Row::secondsPerDay).reversed();
        Comparator<Row> primary = sort == Sort.TIME ? byTime
            : Comparator.comparingDouble(Row::difference).reversed().thenComparing(byTime);
        return primary.thenComparing(Row::key);
    }

    static Cell cell(RuntimeMetrics metrics, Double windowDays) {
        if (metrics == null) {
            return new Cell(0, 0, 0, 0, 0, 0, 0);
        }
        double seconds = TopologyService.seconds(metrics);
        double days = Math.max(1d, windowDays == null ? 1d : windowDays);
        long executions = metrics.getExecuted();
        return new Cell(executions, seconds, seconds / days, executions / days,
            executions == 0 ? 0 : (double) metrics.getRowsScanned() / executions,
            executions == 0 ? 0 : seconds * 1000 / executions, metrics.getRowsAffected());
    }

    private static double perDay(List<Cell> cells) {
        return cells.stream().filter(Objects::nonNull).mapToDouble(Cell::secondsPerDay).sum();
    }

    private static String text(String query) {
        if (query == null) {
            return "";
        }
        String flat = query.replaceAll("\\s+", " ").trim();
        return flat.length() <= TEXT_LENGTH ? flat : flat.substring(0, TEXT_LENGTH) + "…";
    }

    private static Set<String> tables(SqlProfile profile) {
        Set<String> keys = new LinkedHashSet<>();
        if (profile.getTables() == null) {
            return keys;
        }
        for (TableReference table : profile.getTables()) {
            String schema = table.getNormalizedSchemaName() != null ? table.getNormalizedSchemaName()
                : table.getSchemaName() != null ? table.getSchemaName() : profile.getSchemaName();
            String name = table.getNormalizedTableName() != null ? table.getNormalizedTableName() : table.getTableName();
            keys.add((schema == null ? "" : schema + ".") + name);
        }
        return keys;
    }

    static String key(SqlProfile profile) {
        return profile.getNormalizedDigest() != null ? profile.getNormalizedDigest()
            : profile.getDigest() != null ? profile.getDigest() : profile.getId();
    }

    record ServerCorpus(Map<String, SqlProfile> byKey, Double windowDays, double seconds) { }

    private ServerCorpus corpus(String environmentName, String server) {
        SqlRepository repository = repositories.getSqlRepository(environmentName, SourceScope.only(server));
        Map<String, SqlProfile> byKey = new HashMap<>();
        LocalDateTime[] window = new LocalDateTime[2];
        double[] seconds = new double[1];
        repository.getAllAnalyzedSql().forEach(profile -> {
            byKey.putIfAbsent(key(profile), profile);
            RuntimeMetrics metrics = profile.getRuntimeMetrics();
            if (metrics == null) {
                return;
            }
            seconds[0] += TopologyService.seconds(metrics);
            if (metrics.getFirstSeen() != null && (window[0] == null || metrics.getFirstSeen().isBefore(window[0]))) {
                window[0] = metrics.getFirstSeen();
            }
            if (metrics.getLastSeen() != null && (window[1] == null || metrics.getLastSeen().isAfter(window[1]))) {
                window[1] = metrics.getLastSeen();
            }
        });
        Double days = window[0] == null || window[1] == null || window[1].isBefore(window[0]) ? null
            : Duration.between(window[0], window[1]).toSeconds() / 86_400d;
        return new ServerCorpus(byKey, days, seconds[0]);
    }

    private Set<String> loadedSources(String environmentName) {
        Set<String> refs = new HashSet<>();
        if (repositories.getLoadState(environmentName) == null) {
            return refs;
        }
        for (SqlRepository repository : repositories.getRepositories(environmentName)) {
            if (repository.sourceRef() != null) {
                refs.add(repository.sourceRef());
            }
        }
        return refs;
    }

    private Cluster chooseCluster(String environmentName, List<Cluster> clusters, String asked, Set<String> loaded,
                                  Map<String, ServerCorpus> corpora) {
        if (asked != null && !asked.isBlank()) {
            Cluster named = clusters.stream().filter(c -> c.name().equals(asked)).findFirst().orElse(null);
            if (named != null) {
                return named;
            }
        }
        if (clusters.size() <= 1) {
            return clusters.isEmpty() ? null : clusters.getFirst();
        }
        Cluster busiest = clusters.getFirst();
        double most = -1;
        for (Cluster cluster : clusters) {
            double seconds = 0;
            for (DatabaseServer server : cluster.servers()) {
                if (loaded.contains(server.getName())) {
                    seconds += corpora.computeIfAbsent(server.getName(), name -> corpus(environmentName, name))
                        .seconds();
                }
            }
            if (seconds > most) {
                most = seconds;
                busiest = cluster;
            }
        }
        return busiest;
    }
}
