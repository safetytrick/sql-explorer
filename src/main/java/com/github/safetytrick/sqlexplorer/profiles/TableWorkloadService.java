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
import com.github.safetytrick.sqlexplorer.model.WriteTargets;
import org.springframework.stereotype.Service;

@Service
public class TableWorkloadService {

    static final double LONG_STATEMENT_MS = 1_000d;

    static final int LOCK_RISK_LIMIT = 20;

    private static final double PICOS_PER_MILLI = 1_000_000_000d;

    private final SqlRepositoryService sqlRepositoryService;

    public TableWorkloadService(SqlRepositoryService sqlRepositoryService) {
        this.sqlRepositoryService = sqlRepositoryService;
    }

    public WriteRate getWriteRate(TableReference table) {
        List<WriteStatement> statements = new ArrayList<>();
        Map<String, TypeTotals> byType = new LinkedHashMap<>();
        LocalDateTime from = null;
        LocalDateTime to = null;

        for (SqlProfile profile : sqlRepositoryService.getTableQueries(table).toList()) {
            RuntimeMetrics metrics = profile.getRuntimeMetrics();
            if (metrics == null || !WriteTargets.writes(profile, table)) {
                continue;
            }
            String type = profile.getType().toUpperCase(Locale.ROOT);
            WriteStatement statement = new WriteStatement(profile.getDigest(), type, profile.getQuery(),
                WriteTargets.writtenCount(profile) > 1,
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

    public LockRisk getLockRisk(TableReference table) {
        return getLockRisk(table, LONG_STATEMENT_MS);
    }

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

    private static double millis(BigDecimal picos) {
        return picos == null ? 0d : picos.doubleValue() / PICOS_PER_MILLI;
    }

    private static LocalDateTime earliest(LocalDateTime current, LocalDateTime candidate) {
        return candidate == null ? current : current == null || candidate.isBefore(current) ? candidate : current;
    }

    private static LocalDateTime latest(LocalDateTime current, LocalDateTime candidate) {
        return candidate == null ? current : current == null || candidate.isAfter(current) ? candidate : current;
    }

    public record WriteRate(long executions, long rowsAffected, double executionsPerDay, double rowsPerDay,
                            LocalDateTime from, LocalDateTime to,
                            List<WriteTypeTotal> byType, List<WriteStatement> statements) { }

    public record WriteTypeTotal(String type, int statements, long executions, long rowsAffected,
                                 double executionsPerDay, double rowsPerDay) { }

    public record WriteStatement(String digest, String type, String query, boolean multiTable,
                                 long executions, long rowsAffected,
                                 double executionsPerDay, double rowsPerDay,
                                 Double windowDays, LocalDateTime firstSeen, LocalDateTime lastSeen) { }

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
