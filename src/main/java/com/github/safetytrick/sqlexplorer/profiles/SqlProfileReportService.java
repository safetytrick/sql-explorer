package com.github.safetytrick.sqlexplorer.profiles;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryService;
import extensions.com.github.safetytrick.sqlexplorer.avro.SqlProfile.SqlProfileExtension;
import org.springframework.stereotype.Service;

@Service
class SqlProfileReportService {
    private final SqlRepositoryService sqlRepositoryService;

    private static final BigDecimal ONE_THOUSAND = new BigDecimal(1000);
    private Duration picosToDuration(BigDecimal bigDecimal) {
        return Duration.ofNanos(bigDecimal.divide(ONE_THOUSAND, RoundingMode.HALF_UP).longValue());
    }

    private final Map<String, Supplier<Stream<SqlProfile>>> reports = new HashMap<>();

    {
        reports.put("all", this::getAll);
        reports.put("join-across-types", this::getJoinAcrossTypesReport);
        reports.put("slow", this::getSlowQueries);
        reports.put("chatty", this::getChattyQueries);
        reports.put("analysis-failures", this::getAnalysisFailures);
        reports.put("parse-failures", this::getParseFailures);
        reports.put("truncated", this::getTruncatedSql);
        reports.put("full-scan", this::getFullScanSql);
        reports.put("multi-schema", this::getMultiSchema);
    }

    public Stream<SqlProfile> getNamedReport(String name) {
        Supplier<Stream<SqlProfile>> supplier = reports.get(name);
        if (supplier == null) {
            throw new IllegalArgumentException("Invalid report name: " + name);
        }
        return supplier.get();
    }

    private Stream<SqlProfile> getAll() {
        return sqlRepositoryService.getAllAnalyzedSql();
    }

    public SqlProfileReportService(SqlRepositoryService sqlRepositoryService) {
        this.sqlRepositoryService = sqlRepositoryService;
    }

    public Stream<SqlProfile> getJoinAcrossTypesReport() {
        return sqlRepositoryService.getAllAnalyzedSql()
            .filter(analyzedSql -> analyzedSql.getMismatchedJoins() != null &&
                !analyzedSql.getMismatchedJoins().isEmpty());
    }

    private static final Duration SLOW_THRESHOLD = Duration.of(1, ChronoUnit.SECONDS);

    public Stream<SqlProfile> getSlowQueries() {
        return sqlRepositoryService.getAllAnalyzedSql()
            .filter(x -> x.getRuntimeMetrics() != null && picosToDuration(x.getRuntimeMetrics().getAverageTime()).compareTo(SLOW_THRESHOLD) > 0);
    }

    private static final Duration CHATTY_THRESHOLD = Duration.ofMillis(20);

    public Stream<SqlProfile> getChattyQueries() {
        return sqlRepositoryService.getAllAnalyzedSql()
                .filter(x -> x.getRuntimeMetrics() != null && picosToDuration(x.getRuntimeMetrics().getAverageTime()).compareTo(CHATTY_THRESHOLD) < 0);
    }

    public Stream<SqlProfile> getAnalysisFailures() {
        return sqlRepositoryService.getAllAnalyzedSql()
            .filter(SqlProfile::getAnalysisFailed)
            .filter(sql -> !sql.getTruncated())
            .filter(sql -> sql.getAnalysisFailureReason() != null && sql.getAnalysisFailureReason().startsWith("Failure on:"))
            .sorted(SqlProfileExtension.comparator);
    }

    public Stream<SqlProfile> getParseFailures() {
        return sqlRepositoryService.getAllAnalyzedSql()
            .filter(SqlProfile::getAnalysisFailed)
            .filter(sql -> !sql.getTruncated())
            .filter(sql -> sql.getAnalysisFailureReason() != null && !sql.getAnalysisFailureReason().startsWith("Failure on:"))
            .sorted(SqlProfileExtension.comparator);
    }


    public Stream<SqlProfile> getTruncatedSql() {
        return sqlRepositoryService.getAllAnalyzedSql()
            .filter(SqlProfile::getTruncated)
            .sorted(SqlProfileExtension.comparator);
    }

    public Stream<SqlProfile> getFullScanSql() {
        return sqlRepositoryService.getAllAnalyzedSql()
            .filter(sql -> sql.getRuntimeMetrics() != null && sql.getRuntimeMetrics().getFullTableScan())
            .sorted(SqlProfileExtension.comparator);
    }

    public Stream<SqlProfile> getMultiSchema() {
        return sqlRepositoryService.getAllAnalyzedSql()
            .filter(SqlProfile::getIsMultiSchema)
            .sorted(SqlProfileExtension.comparator);
    }
}
