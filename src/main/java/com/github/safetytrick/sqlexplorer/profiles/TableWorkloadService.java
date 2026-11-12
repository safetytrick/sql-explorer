package com.github.safetytrick.sqlexplorer.profiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryService;
import com.github.safetytrick.sqlexplorer.model.CapturedRate;
import com.github.safetytrick.sqlexplorer.model.QueryKind;
import org.springframework.stereotype.Service;

/**
 * What a schema migration needs to know about a table's traffic: how often it
 * is written (sqlx-n5j.10), and which statements would hold up DDL on it
 * (sqlx-n5j.9).
 *
 * <p>Both read only the captured statement statistics — Performance Schema's
 * per-digest counters — so both share their limits. The counters are
 * cumulative since the last reset, with a first and last seen and nothing in
 * between: there is no time-of-day distribution, so neither can say
 * <em>when</em> a table is busy or quiet. Captures do not fix that either; a
 * capture is the same cumulative snapshot taken later, kept per run rather than
 * per hour, and none is taken on a schedule unless an environment sets
 * {@code pullCron}. "Quietest hours" therefore is not offered: the data cannot
 * support it, and an answer it cannot support is worse than none.
 *
 * <p>An unknown table answers zeros and empty lists, never an error — the same
 * contract {@code /table/{s}/{t}/stats} keeps, which the table lens relies on.
 */
@Service
public class TableWorkloadService {

    /**
     * A statement whose slowest execution reached this is "long" for lock
     * purposes: a DDL arriving while it runs waits at least this long, and so
     * does every query on the table that arrives after the DDL.
     */
    static final double LONG_STATEMENT_MS = 1_000d;

    /** The lock-risk list is a ranking, so it is cut; the totals still cover every statement. */
    static final int LOCK_RISK_LIMIT = 20;

    private static final double PICOS_PER_MILLI = 1_000_000_000d;

    private final SqlRepositoryService sqlRepositoryService;

    public TableWorkloadService(SqlRepositoryService sqlRepositoryService) {
        this.sqlRepositoryService = sqlRepositoryService;
    }

    /** INSERT/UPDATE/DELETE/REPLACE on the table, per statement and per type, as daily averages. */
    public WriteRate getWriteRate(TableReference table) {
        List<WriteStatement> statements = new ArrayList<>();
        Map<String, TypeTotals> byType = new LinkedHashMap<>();
        LocalDateTime from = null;
        LocalDateTime to = null;

        for (SqlProfile profile : sqlRepositoryService.getTableQueries(table).toList()) {
            RuntimeMetrics metrics = profile.getRuntimeMetrics();
            if (metrics == null || !QueryKind.of(profile).isWrite()) {
                continue;
            }
            String type = profile.getType().toUpperCase(Locale.ROOT);
            WriteStatement statement = new WriteStatement(profile.getDigest(), type, profile.getQuery(),
                tableCount(profile) > 1,
                metrics.getExecuted(), metrics.getRowsAffected(),
                CapturedRate.perDay(metrics.getExecuted(), metrics),
                CapturedRate.perDay(metrics.getRowsAffected(), metrics),
                CapturedRate.windowDays(metrics), metrics.getFirstSeen(), metrics.getLastSeen());
            statements.add(statement);
            byType.computeIfAbsent(type, TypeTotals::new).add(statement);
            from = earliest(from, metrics.getFirstSeen());
            to = latest(to, metrics.getLastSeen());
        }

        statements.sort(Comparator.comparingDouble(WriteStatement::executionsPerDay).reversed());
        List<WriteTypeTotal> types = byType.values().stream()
            .map(TypeTotals::total)
            .sorted(Comparator.comparingDouble(WriteTypeTotal::executionsPerDay).reversed())
            .toList();
        return new WriteRate(
            statements.stream().mapToLong(WriteStatement::executions).sum(),
            statements.stream().mapToLong(WriteStatement::rowsAffected).sum(),
            statements.stream().mapToDouble(WriteStatement::executionsPerDay).sum(),
            statements.stream().mapToDouble(WriteStatement::rowsPerDay).sum(),
            from, to, types, List.copyOf(statements));
    }

    /**
     * The statements on the table that would keep a DDL waiting, longest
     * first. Ranked by max time, because one slow execution is what a DDL
     * collides with; the average says how often that is likely.
     */
    public LockRisk getLockRisk(TableReference table) {
        return getLockRisk(table, LONG_STATEMENT_MS);
    }

    /**
     * As {@link #getLockRisk(TableReference)}, counting a statement as long
     * from {@code longThresholdMs}: the DDL pre-flight lets its caller choose.
     */
    public LockRisk getLockRisk(TableReference table, double longThresholdMs) {
        double threshold = longThresholdMs > 0 ? longThresholdMs : LONG_STATEMENT_MS;
        List<LockStatement> statements = new ArrayList<>();
        for (SqlProfile profile : sqlRepositoryService.getTableQueries(table).toList()) {
            RuntimeMetrics metrics = profile.getRuntimeMetrics();
            if (metrics == null) {
                continue;
            }
            statements.add(new LockStatement(profile.getDigest(),
                profile.getType() == null ? null : profile.getType().toUpperCase(Locale.ROOT),
                profile.getQuery(),
                metrics.getExecuted(),
                CapturedRate.perDay(metrics.getExecuted(), metrics),
                millis(metrics.getAverageTime()),
                millis(metrics.getMaxTime()),
                millis(metrics.getTotalTime()),
                millis(metrics.getLockTime()),
                CapturedRate.windowDays(metrics)));
        }
        statements.sort(Comparator.comparingDouble(LockStatement::maxTimeMs).reversed()
            .thenComparing(Comparator.comparingDouble(LockStatement::executionsPerDay).reversed()));

        List<LockStatement> longOnes = statements.stream()
            .filter(statement -> statement.maxTimeMs() >= threshold)
            .toList();
        return new LockRisk(
            statements.size(),
            threshold,
            longOnes.size(),
            longOnes.stream().mapToDouble(LockStatement::executionsPerDay).sum(),
            statements.isEmpty() ? 0d : statements.getFirst().maxTimeMs(),
            statements.stream().mapToDouble(LockStatement::averageTimeMs).max().orElse(0d),
            false,
            List.copyOf(statements.subList(0, Math.min(LOCK_RISK_LIMIT, statements.size()))));
    }

    private static int tableCount(SqlProfile profile) {
        return profile.getTables() == null ? 0 : profile.getTables().size();
    }

    private static double millis(BigDecimal picos) {
        return picos == null ? 0d : picos.doubleValue() / PICOS_PER_MILLI;
    }

    private static LocalDateTime earliest(LocalDateTime current, LocalDateTime candidate) {
        return candidate == null ? current : current == null || candidate.isBefore(current) ? candidate : current;
    }

    private static LocalDateTime latest(LocalDateTime current, LocalDateTime candidate) {
        return candidate == null ? current : current == null || candidate.isAfter(current) ? candidate : current;
    }

    /**
     * Writes to one table. Every rate is a daily <em>average</em> over each
     * statement's captured window, floored at one day — see {@link CapturedRate}.
     *
     * @param from earliest first-seen among the writing statements; null when none
     * @param to   latest last-seen among them
     */
    public record WriteRate(long executions, long rowsAffected, double executionsPerDay, double rowsPerDay,
                            LocalDateTime from, LocalDateTime to,
                            List<WriteTypeTotal> byType, List<WriteStatement> statements) { }

    public record WriteTypeTotal(String type, int statements, long executions, long rowsAffected,
                                 double executionsPerDay, double rowsPerDay) { }

    /**
     * One writing statement.
     *
     * @param multiTable the statement names other tables too. Its counts are
     *                   the statement's, not this table's: a multi-table UPDATE
     *                   reports rows changed in every table it writes, and an
     *                   INSERT … SELECT is counted against the table it reads.
     * @param windowDays lastSeen − firstSeen, unfloored; null when unknown
     */
    public record WriteStatement(String digest, String type, String query, boolean multiTable,
                                 long executions, long rowsAffected,
                                 double executionsPerDay, double rowsPerDay,
                                 Double windowDays, LocalDateTime firstSeen, LocalDateTime lastSeen) { }

    /**
     * The statements that would hold up DDL on a table.
     *
     * @param statementCount      statements touching the table with runtime metrics
     * @param longThresholdMs     max time from which a statement counts as long
     * @param longStatements      how many statements reached it
     * @param longExecutionsPerDay how often those statements run, summed — an
     *                            upper bound on how often a slow execution is in
     *                            flight, since most runs are nearer the average
     * @param maxTimeMs           the slowest single execution of any statement
     * @param maxAverageTimeMs    the highest average of any statement
     * @param quietHoursAvailable always false: see the class note
     * @param statements          the longest first, cut at {@value #LOCK_RISK_LIMIT}
     */
    public record LockRisk(int statementCount, double longThresholdMs, int longStatements,
                           double longExecutionsPerDay, double maxTimeMs, double maxAverageTimeMs,
                           boolean quietHoursAvailable, List<LockStatement> statements) { }

    public record LockStatement(String digest, String type, String query, long executions,
                                double executionsPerDay, double averageTimeMs, double maxTimeMs,
                                double totalTimeMs, double lockTimeMs, Double windowDays) { }

    private static final class TypeTotals {
        private final String type;
        private int statements;
        private long executions;
        private long rowsAffected;
        private double executionsPerDay;
        private double rowsPerDay;

        TypeTotals(String type) {
            this.type = type;
        }

        void add(WriteStatement statement) {
            statements++;
            executions += statement.executions();
            rowsAffected += statement.rowsAffected();
            executionsPerDay += statement.executionsPerDay();
            rowsPerDay += statement.rowsPerDay();
        }

        WriteTypeTotal total() {
            return new WriteTypeTotal(type, statements, executions, rowsAffected, executionsPerDay, rowsPerDay);
        }
    }
}
