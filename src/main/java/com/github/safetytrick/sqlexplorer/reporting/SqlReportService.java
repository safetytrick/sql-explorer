package com.github.safetytrick.sqlexplorer.reporting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.safetytrick.sqlexplorer.core.AnalyzedSql;
import com.github.safetytrick.sqlexplorer.service.SqlRepositoryService;

@Service
public class SqlReportService {
    private static final Logger log = LoggerFactory.getLogger(SqlReportService.class);

    private final SqlRepositoryService sqlRepositoryService;

    private static final BigDecimal ONE_THOUSAND = new BigDecimal(1000);
    private Duration picosToDuration(BigDecimal bigDecimal) {
        return Duration.ofNanos(bigDecimal.divide(ONE_THOUSAND, RoundingMode.HALF_UP).longValue());
    }

    private final Map<String, Supplier<Stream<AnalyzedSql>>> reports = new HashMap<>();

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

    public Stream<AnalyzedSql> getNamedReport(String name) {
        Supplier<Stream<AnalyzedSql>> supplier = reports.get(name);
        if (supplier == null) {
            throw new IllegalArgumentException("Invalid report name: " + name);
        }
        return supplier.get();
    }

    private Stream<AnalyzedSql> getAll() {
        return sqlRepositoryService.getAllAnalyzedSql();
    }

    public SqlReportService(SqlRepositoryService sqlRepositoryService) {
        this.sqlRepositoryService = sqlRepositoryService;
    }

    public Stream<AnalyzedSql> getJoinAcrossTypesReport() {
        return sqlRepositoryService.getAllAnalyzedSql()
            .filter(analyzedSql -> analyzedSql.getMismatchedJoins() != null &&
                !analyzedSql.getMismatchedJoins().isEmpty());
    }

    private static final Duration SLOW_THRESHOLD = Duration.of(1, ChronoUnit.SECONDS);

    public Stream<AnalyzedSql> getSlowQueries() {
        return sqlRepositoryService.getAllAnalyzedSql()
            .filter(x -> x.getRuntimeMetrics() != null && picosToDuration(x.getRuntimeMetrics().getAverageTime()).compareTo(SLOW_THRESHOLD) > 0);
    }

    private static final Duration CHATTY_THRESHOLD = Duration.ofMillis(20);

    public Stream<AnalyzedSql> getChattyQueries() {
        return sqlRepositoryService.getAllAnalyzedSql()
                .filter(x -> x.getRuntimeMetrics() != null && picosToDuration(x.getRuntimeMetrics().getAverageTime()).compareTo(CHATTY_THRESHOLD) < 0);
    }

    public Stream<AnalyzedSql> getAnalysisFailures() {
        return sqlRepositoryService.getAllAnalyzedSql()
            .filter(AnalyzedSql::isAnalysisFailed)
            .filter(sql -> !sql.isTruncated())
            .filter(sql -> sql.getAnalysisFailureReason() != null && sql.getAnalysisFailureReason().startsWith("Failure on:"))
            .sorted();
    }

    public Stream<AnalyzedSql> getParseFailures() {
        return sqlRepositoryService.getAllAnalyzedSql()
            .filter(AnalyzedSql::isAnalysisFailed)
            .filter(sql -> !sql.isTruncated())
            .filter(sql -> sql.getAnalysisFailureReason() != null && !sql.getAnalysisFailureReason().startsWith("Failure on:"))
            .sorted();
    }


    public Stream<AnalyzedSql> getTruncatedSql() {
        return sqlRepositoryService.getAllAnalyzedSql()
            .filter(AnalyzedSql::isTruncated)
            .sorted();
    }

    public Stream<AnalyzedSql> getFullScanSql() {
        return sqlRepositoryService.getAllAnalyzedSql()
            .filter(sql -> sql.getRuntimeMetrics() != null && sql.getRuntimeMetrics().isFullTableScan())
            .sorted();
    }

    public Stream<AnalyzedSql> getMultiSchema() {
        return sqlRepositoryService.getAllAnalyzedSql()
            .filter(AnalyzedSql::isMultiSchema)
            .sorted();
    }
}
