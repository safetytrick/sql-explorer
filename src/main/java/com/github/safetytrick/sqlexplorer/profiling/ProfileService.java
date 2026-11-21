package com.github.safetytrick.sqlexplorer.profiling;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.safetytrick.sqlexplorer.auth.User;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.environment.Cluster;
import com.github.safetytrick.sqlexplorer.environment.DatabaseServer;
import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.Environment.ClusterSampling;
import com.github.safetytrick.sqlexplorer.environment.SamplingTarget;
import com.github.safetytrick.sqlexplorer.environment.SamplingUnavailableException;
import com.github.safetytrick.sqlexplorer.profiling.ProfilePlan.Method;
import com.github.safetytrick.sqlexplorer.profiling.ProfileResult.ServerResult;
import com.github.safetytrick.sqlexplorer.profiling.ProfileSql.Placeholder;
import com.github.safetytrick.sqlexplorer.profiling.ProfileSql.Window;
import com.github.safetytrick.sqlexplorer.profiling.ProfileSql.WindowKind;
import com.github.safetytrick.sqlexplorer.schema.TableStats;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Service
public class ProfileService {

    public static final Duration STALE_AFTER = Duration.ofDays(30);

    private final ProfileRunRepository runs;
    private final JsonMapper json;
    private final Clock clock;
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "profile-run");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Long, Progress> progress = new ConcurrentHashMap<>();

    public ProfileService(ProfileRunRepository runs, JsonMapper json, Clock clock) {
        this.runs = runs;
        this.json = json;
        this.clock = clock;
    }

    @jakarta.annotation.PostConstruct
    void closeAbandoned() {
        for (ProfileRun run : runs.findAllByStatus(ProfileRun.RUNNING)) {
            run.setStatus(ProfileRun.FAILED);
            run.setError("the server restarted while this run was in progress");
            run.setFinishedOn(clock.instant());
            runs.save(run);
        }
    }

    @PreDestroy
    void stop() {
        progress.values().forEach(p -> p.cancelled.set(true));
        executor.shutdownNow();
    }

    public record TargetView(String cluster, String server, Long rowsEstimate, boolean available, String reason,
                             long maxRows, long maxStatements) { }

    public record ProfileRequestPlan(String environment, String schema, String table, Method method,
                                     List<String> key, List<ColumnView> columns, String fullScanColumn,
                                     String probeServer, String unavailable, List<TargetView> targets,
                                     List<String> statements, long maxRows, long maxStatements,
                                     long statementTimeLimitMs, long pauseMs, int idsPerSignature,
                                     String windows) {

        public boolean runnable() {
            return unavailable == null && targets.stream().anyMatch(TargetView::available);
        }
    }

    public record ColumnView(String name, String type, String characterSet, String collation,
                             List<String> signatures) { }

    public ProfileRequestPlan plan(Environment environment, TableStructure table, String probeServer,
                                   List<String> columns, String fullScanColumn) {
        ProfilePlan plan = ProfilePlan.of(table, columns, fullScanColumn);
        List<TargetView> targets = targets(environment, table, plan, probeServer).stream()
            .map(Target::view).toList();
        String unavailable = plan.unavailable();
        if (unavailable == null && targets.stream().noneMatch(TargetView::available)) {
            unavailable = targets.isEmpty() ? "no cluster of this environment has the table"
                : targets.getFirst().reason();
        }
        long maxRows = sumOrUnknown(targets.stream().filter(TargetView::available).map(TargetView::maxRows).toList());
        long maxStatements = sumOrUnknown(targets.stream().filter(TargetView::available)
            .map(TargetView::maxStatements).toList());
        return new ProfileRequestPlan(environment.getName(), table.schema(), table.name(), plan.method(), plan.key(),
            plan.columns().stream().map(c -> new ColumnView(c.name(), c.type(), c.characterSet(), c.collation(),
                c.signatures().stream().map(Signature::label).toList())).toList(),
            fullScanColumn, probeServer, unavailable, targets, plan.runnable() ? exampleStatements(plan) : List.of(),
            maxRows, maxStatements, ProfilePlan.LIMITS.maxExecutionTime().toMillis(), ProfilePlan.PAUSE.toMillis(),
            ProfilePlan.IDS_PER_SIGNATURE, windowsDescription(plan));
    }

    private static long sumOrUnknown(List<Long> values) {
        return values.stream().anyMatch(v -> v < 0) ? -1 : values.stream().mapToLong(Long::longValue).sum();
    }

    static List<String> exampleStatements(ProfilePlan plan) {
        ProfileSql sql = new ProfileSql(plan);
        List<String> out = new ArrayList<>();
        switch (plan.method()) {
            case WINDOWS -> {
                out.add(sql.bounds().display());
                out.add(sql.aggregate(new Window(WindowKind.HEAD, null, ProfilePlan.HEAD_ROWS, "")).display());
                out.add(sql.aggregate(new Window(WindowKind.TAIL, new Placeholder(":head_last_key"),
                    ProfilePlan.TAIL_ROWS, "")).display());
                out.add(sql.aggregate(new Window(WindowKind.FROM, new Placeholder(":window_start"),
                    new Placeholder(":tail_first_key"), ProfilePlan.MIDDLE_ROWS, "")).display());
            }
            case ENDS -> {
                out.add(sql.aggregate(new Window(WindowKind.HEAD, null, ProfilePlan.HEAD_ROWS, "")).display());
                out.add(sql.aggregate(new Window(WindowKind.TAIL, null, ProfilePlan.TAIL_ROWS, "")).display());
            }
            case FULL_SCAN -> out.add(sql.aggregate(new Window(WindowKind.AFTER, new Placeholder(":last_key_read"),
                ProfilePlan.FULL_SCAN_CHUNK, "")).display());
            case NONE -> { }
        }
        plan.columns().stream().filter(c -> c.signatures().stream().anyMatch(Signature::listsCharacters))
            .findFirst().ifPresent(column -> out.add(sql.characters(
                new Window(WindowKind.HEAD, null, ProfilePlan.HEAD_ROWS, ""), column,
                column.signatures().stream().filter(Signature::listsCharacters).toList()).display()));
        return out;
    }

    private static String windowsDescription(ProfilePlan plan) {
        return switch (plan.method()) {
            case WINDOWS -> "the first " + ProfilePlan.HEAD_ROWS + " and last " + ProfilePlan.TAIL_ROWS
                + " rows by " + String.join(", ", plan.key()) + ", then " + ProfilePlan.MIDDLE_WINDOWS
                + " windows of " + ProfilePlan.MIDDLE_ROWS + " rows evenly spaced between them; no window "
                + "overlaps another, and on a small table they meet and read every row. A column with findings gets one more statement per window listing the matched "
                + "characters, over the same rows.";
            case ENDS -> "the first " + ProfilePlan.HEAD_ROWS + " and last " + ProfilePlan.TAIL_ROWS + " rows by "
                + String.join(", ", plan.key()) + " only: the key is not a single integer column, so no key "
                + "between can be computed.";
            case FULL_SCAN -> "every row, " + ProfilePlan.FULL_SCAN_CHUNK + " at a time in " + plan.key().getFirst()
                + " order, each chunk after the last key read.";
            case NONE -> "nothing";
        };
    }

    private record Target(Cluster cluster, SamplingTarget target, Long rowsEstimate, String reason,
                          ProfilePlan plan) {

        TargetView view() {
            boolean available = target != null && reason == null;
            return new TargetView(cluster == null ? null : cluster.name(),
                target != null ? target.server().getName() : null, rowsEstimate, available, reason,
                available ? plan.maxRows(rowsEstimate) : 0, available ? plan.maxStatements(rowsEstimate) : 0);
        }
    }

    private List<Target> targets(Environment environment, TableStructure table, ProfilePlan plan, String probe) {
        TableReference reference = TableReference.newTableReference(table.schema(), table.name());
        if (probe != null && !probe.isBlank()) {
            Cluster cluster = environment.getClusters().stream()
                .filter(c -> c.servers().stream().anyMatch(s -> s.getName().equals(probe))).findFirst().orElse(null);
            try {
                SamplingTarget target = environment.samplingTargetOn(probe, ProfilePlan.LIMITS);
                return List.of(new Target(cluster, target, rowsEstimate(table, cluster), null, plan));
            } catch (SamplingUnavailableException e) {
                return List.of(new Target(cluster, null, null, e.getMessage(), plan));
            }
        }
        List<Target> out = new ArrayList<>();
        for (ClusterSampling sampling : environment.samplingByCluster(ProfilePlan.LIMITS)) {
            if (!carries(sampling.cluster(), reference)) {
                continue;
            }
            out.add(new Target(sampling.cluster(), sampling.target(), rowsEstimate(table, sampling.cluster()),
                sampling.reason(), plan));
        }
        return out;
    }

    private static boolean carries(Cluster cluster, TableReference reference) {
        try {
            return cluster.readSchema().schema().getTable(reference) != null;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static Long rowsEstimate(TableStructure table, Cluster cluster) {
        if (table.stats() == null || cluster == null) {
            return table.rowsEstimate();
        }
        List<String> names = cluster.servers().stream().map(DatabaseServer::getName).toList();
        return table.stats().stream().filter(s -> names.contains(s.server())).map(TableStats::rowsEstimate)
            .filter(Objects::nonNull).findFirst().orElse(table.rowsEstimate());
    }

    private static final class Progress {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicInteger statements = new AtomicInteger();
    }

    public ProfileRun start(Environment environment, TableStructure table, String probeServer, List<String> columns,
                            String fullScanColumn, User requestedBy) {
        ProfileRequestPlan shown = plan(environment, table, probeServer, columns, fullScanColumn);
        if (!shown.runnable()) {
            throw new IllegalArgumentException(shown.unavailable() == null ? "nothing to run" : shown.unavailable());
        }
        ProfilePlan plan = ProfilePlan.of(table, columns, fullScanColumn);
        List<Target> targets = targets(environment, table, plan, probeServer).stream()
            .filter(t -> t.view().available()).toList();

        ProfileRun run = new ProfileRun();
        run.setEnvironmentName(environment.getName());
        run.setSchemaName(table.schema());
        run.setTableName(table.name());
        run.setProbeServer(probeServer == null || probeServer.isBlank() ? null : probeServer);
        run.setMethod(plan.method().name());
        run.setColumnsList(String.join(",", plan.columns().stream().map(ProfilePlan.ProfiledColumn::name).toList()));
        run.setStatus(ProfileRun.RUNNING);
        run.setRequestedBy(requestedBy);
        run.setStartedOn(clock.instant());
        run.setPlanJson(json.writeValueAsString(shown));
        ProfileRun saved = runs.save(run);

        Progress tracking = new Progress();
        progress.put(saved.getId(), tracking);
        executor.execute(() -> execute(saved.getId(), plan, targets, tracking));
        return saved;
    }

    private void execute(long id, ProfilePlan plan, List<Target> targets, Progress tracking) {
        List<ServerResult> results;
        String error = null;
        try {
            List<Future<ServerResult>> pending = targets.stream().map(t -> executor.submit(() ->
                new ProfileRunner(plan, tracking.cancelled::get, tracking.statements::incrementAndGet)
                    .run(t.cluster() == null ? null : t.cluster().name(), t.target(), t.rowsEstimate()))).toList();
            results = new ArrayList<>();
            for (Future<ServerResult> future : pending) {
                results.add(future.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            results = List.of();
            error = "interrupted";
        } catch (Exception e) {
            log.warn("Profile run {} failed", id, e);
            results = List.of();
            error = e.getMessage();
        }
        ProfileResult result = new ProfileResult(results, Verdicts.combine(results), Verdicts.confidence(results));
        ProfileRun run = runs.findById(id).orElseThrow();
        run.setFinishedOn(clock.instant());
        run.setRowsRead(results.stream().mapToLong(ServerResult::rowsRead).sum());
        run.setStatements(results.stream().mapToInt(ServerResult::statementCount).sum());
        run.setResultJson(json.writeValueAsString(result));
        run.setError(error);
        run.setStatus(tracking.cancelled.get() ? ProfileRun.CANCELLED
            : error != null || results.isEmpty() || results.stream().allMatch(r -> "failed".equals(r.status()))
            ? ProfileRun.FAILED : ProfileRun.DONE);
        runs.save(run);
        progress.remove(id);
    }

    public boolean cancel(long id) {
        Progress tracking = progress.get(id);
        if (tracking == null) {
            return false;
        }
        tracking.cancelled.set(true);
        return true;
    }

    public record RunSummary(long id, String environment, String schema, String table, String probeServer,
                             String method, List<String> columns, String status, String requestedBy,
                             Instant startedOn, Instant finishedOn, long rowsRead, int statements,
                             int statementsSoFar, boolean stale) { }

    public record RunDetail(RunSummary run, ProfileRequestPlan plan, ProfileResult result, String error) { }

    public List<RunSummary> runs(String environment, String schema, String table) {
        return runs.findAllByEnvironmentNameAndSchemaNameAndTableNameOrderByIdDesc(environment, schema, table)
            .stream().map(this::summary).toList();
    }

    public Optional<RunDetail> run(long id) {
        return runs.findById(id).map(run -> new RunDetail(summary(run),
            run.getPlanJson() == null ? null : json.readValue(run.getPlanJson(), ProfileRequestPlan.class),
            run.getResultJson() == null ? null : json.readValue(run.getResultJson(), ProfileResult.class),
            run.getError()));
    }

    private RunSummary summary(ProfileRun run) {
        Progress tracking = progress.get(run.getId());
        Instant finished = run.getFinishedOn();
        boolean stale = finished != null && finished.plus(STALE_AFTER).isBefore(clock.instant());
        return new RunSummary(run.getId(), run.getEnvironmentName(), run.getSchemaName(), run.getTableName(),
            run.getProbeServer(), run.getMethod(),
            run.getColumnsList() == null || run.getColumnsList().isEmpty() ? List.of()
                : List.of(run.getColumnsList().split(",")),
            run.getStatus(), run.getRequestedBy() == null ? null : run.getRequestedBy().getEmail(),
            run.getStartedOn(), finished, run.getRowsRead(), run.getStatements(),
            tracking == null ? run.getStatements() : tracking.statements.get(), stale);
    }
}
