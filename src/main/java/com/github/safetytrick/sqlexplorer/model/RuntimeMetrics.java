package com.github.safetytrick.sqlexplorer.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.github.safetytrick.sqlexplorer.evidence.source.MeteredSql;

public class RuntimeMetrics {

    private final boolean fullTableScan;
    private final long executed;
    private final long errors;
    private final long warnings;
    private final BigDecimal totalTime;
    private final BigDecimal maxTime;
    private final BigDecimal averageTime;
    private final long rowsSent;
    private final long averageRowsSent;
    private final long rowsScanned;
    private final long averageRowsScanned;
    private final long tempTables;
    private final long tempDiskTables;
    private final long rowsSorted;
    private final long sortMergePasses;
    private final String digest;
    private final LocalDateTime lastSeen;
    private final LocalDateTime firstSeen;
    private final BigDecimal lockTime;
    private final long rowsAffected;
    private final long averageRowsAffected;
    private final List<String> digestList;

    private RuntimeMetrics(boolean fullTableScan,
                           long executed,
                           long errors,
                           long warnings,
                           BigDecimal totalTime,
                           BigDecimal maxTime,
                           BigDecimal averageTime,
                           long rowsSent,
                           long averageRowsSent,
                           long rowsScanned,
                           long averageRowsScanned,
                           long tempTables,
                           long tempDiskTables,
                           long rowsSorted,
                           long sortMergePasses,
                           String digest,
                           List<String> digestList,
                           LocalDateTime lastSeen,
                           LocalDateTime firstSeen,
                           BigDecimal lockTime,
                           long rowsAffected,
                           long averageRowsAffected) {
        this.fullTableScan = fullTableScan;
        this.executed = executed;
        this.errors = errors;
        this.warnings = warnings;
        this.totalTime = totalTime;
        this.maxTime = maxTime;
        this.averageTime = averageTime;
        this.rowsSent = rowsSent;
        this.averageRowsSent = averageRowsSent;
        this.rowsScanned = rowsScanned;
        this.averageRowsScanned = averageRowsScanned;
        this.tempTables = tempTables;
        this.tempDiskTables = tempDiskTables;
        this.rowsSorted = rowsSorted;
        this.sortMergePasses = sortMergePasses;
        this.digest = digest;
        if (digestList == null) {
            this.digestList = Collections.singletonList(this.digest);
        } else {
            this.digestList = digestList;
        }

        this.lastSeen = lastSeen;
        this.firstSeen = firstSeen;
        this.lockTime = lockTime;
        this.rowsAffected = rowsAffected;
        this.averageRowsAffected = averageRowsAffected;
    }

    public boolean getFullTableScan() {
        return fullTableScan;
    }

    public long getExecuted() {
        return executed;
    }

    public long getErrors() {
        return errors;
    }

    public long getWarnings() {
        return warnings;
    }

    public BigDecimal getTotalTime() {
        return totalTime;
    }

    public BigDecimal getMaxTime() {
        return maxTime;
    }

    public BigDecimal getAverageTime() {
        return averageTime;
    }

    public long getRowsSent() {
        return rowsSent;
    }

    public long getAverageRowsSent() {
        return averageRowsSent;
    }

    public long getRowsScanned() {
        return rowsScanned;
    }

    public long getAverageRowsScanned() {
        return averageRowsScanned;
    }

    public long getTempTables() {
        return tempTables;
    }

    public long getTempDiskTables() {
        return tempDiskTables;
    }

    public long getRowsSorted() {
        return rowsSorted;
    }

    public long getSortMergePasses() {
        return sortMergePasses;
    }

    public boolean isFullTableScan() {
        return fullTableScan;
    }

    public BigDecimal getLockTime() {
        return lockTime;
    }

    public long getRowsAffected() {
        return rowsAffected;
    }

    public long getAverageRowsAffected() {
        return averageRowsAffected;
    }

    public String getDigest() {
        return digest;
    }

    public List<String> getDigestList() {
        return digestList;
    }

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    public LocalDateTime getLastSeen() {
        return lastSeen;
    }

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    public LocalDateTime getFirstSeen() {
        return firstSeen;
    }

    public static RuntimeMetrics merged(List<RuntimeMetrics> toMerge) {
        if (toMerge == null || toMerge.isEmpty()) {
            throw new IllegalArgumentException("toMerge must contain at least one metric");
        }
        if (toMerge.size() == 1) {
            return toMerge.get(0);
        }
        BigDecimal totalTime = toMerge.stream().map(RuntimeMetrics::getTotalTime).reduce(BigDecimal::add).orElse(BigDecimal.ZERO);
        long executed = toMerge.stream().mapToLong(RuntimeMetrics::getExecuted).sum();
        long rowsSent = toMerge.stream().mapToLong(RuntimeMetrics::getRowsSent).sum();
        long rowsScanned = toMerge.stream().mapToLong(RuntimeMetrics::getRowsScanned).sum();
        long rowsAffected = toMerge.stream().mapToLong(RuntimeMetrics::getRowsAffected).sum();
        return builder().fullTableScan(toMerge.stream().anyMatch(RuntimeMetrics::isFullTableScan))
            .executed(executed)
            .errors(toMerge.stream().mapToLong(RuntimeMetrics::getErrors).sum())
            .warnings(toMerge.stream().mapToLong(RuntimeMetrics::getWarnings).sum())
            .totalTime(totalTime)
            .maxTime(toMerge.stream().map(RuntimeMetrics::getMaxTime).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO))
            .averageTime(totalTime.divide(BigDecimal.valueOf(executed), RoundingMode.HALF_UP))
            .rowsSent(rowsSent)
            .averageRowsSent(rowsSent / executed)
            .rowsScanned(rowsScanned)
            .averageRowsScanned(rowsScanned / executed)
            .tempTables(toMerge.stream().mapToLong(RuntimeMetrics::getTempTables).sum())
            .tempDiskTables(toMerge.stream().mapToLong(RuntimeMetrics::getTempDiskTables).sum())
            .rowsSorted(toMerge.stream().mapToLong(RuntimeMetrics::getRowsSorted).sum())
            .sortMergePasses(toMerge.stream().mapToLong(RuntimeMetrics::getSortMergePasses).sum())
            .lastSeen(toMerge.stream().map(RuntimeMetrics::getLastSeen).max(Comparator.naturalOrder()).orElse(null))
            .firstSeen(toMerge.stream().map(RuntimeMetrics::getFirstSeen).min(Comparator.naturalOrder()).orElse(null))
            .lockTime(toMerge.stream().map(RuntimeMetrics::getLockTime).reduce(BigDecimal::add).orElse(BigDecimal.ZERO))
            .rowsAffected(rowsAffected)
            .averageRowsAffected(rowsAffected / executed)
            .digest(toMerge.get(0).getDigest())
            .digestList(toMerge.stream().flatMap(metrics -> metrics.getDigestList().stream()).collect(Collectors.toList()))
            .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private Builder() {}


        private boolean fullTableScan;
        private long executed;
        private long errors;
        private long warnings;
        private BigDecimal totalTime;
        private BigDecimal maxTime;
        private BigDecimal averageTime;
        private long rowsSent;
        private long averageRowsSent;
        private long rowsScanned;
        private long averageRowsScanned;
        private long tempTables;
        private long tempDiskTables;
        private long rowsSorted;
        private long sortMergePasses;
        private String digest;
        private List<String> digestList;
        private LocalDateTime lastSeen;
        private LocalDateTime firstSeen;
        private BigDecimal lockTime;
        private long rowsAffected;
        private long averageRowsAffected;

        public Builder fullTableScan(boolean fullTableScan) {
            this.fullTableScan = fullTableScan;
            return this;
        }

        public Builder executed(long executed) {
            this.executed = executed;
            return this;
        }

        public Builder errors(long errors) {
            this.errors = errors;
            return this;
        }

        public Builder warnings(long warnings) {
            this.warnings = warnings;
            return this;
        }

        public Builder totalTime(BigDecimal totalTime) {
            this.totalTime = totalTime;
            return this;
        }

        public Builder maxTime(BigDecimal maxTime) {
            this.maxTime = maxTime;
            return this;
        }

        public Builder averageTime(BigDecimal averageTime) {
            this.averageTime = averageTime;
            return this;
        }

        public Builder rowsSent(long rowsSent) {
            this.rowsSent = rowsSent;
            return this;
        }

        public Builder averageRowsSent(long averageRowsSent) {
            this.averageRowsSent = averageRowsSent;
            return this;
        }

        public Builder rowsScanned(long rowsScanned) {
            this.rowsScanned = rowsScanned;
            return this;
        }

        public Builder averageRowsScanned(long averageRowsScanned) {
            this.averageRowsScanned = averageRowsScanned;
            return this;
        }

        public Builder tempTables(long tempTables) {
            this.tempTables = tempTables;
            return this;
        }

        public Builder tempDiskTables(long tempDiskTables) {
            this.tempDiskTables = tempDiskTables;
            return this;
        }

        public Builder rowsSorted(long rowsSorted) {
            this.rowsSorted = rowsSorted;
            return this;
        }

        public Builder sortMergePasses(long sortMergePasses) {
            this.sortMergePasses = sortMergePasses;
            return this;
        }

        public Builder digest(String digest) {
            this.digest = digest;
            return this;
        }

        public Builder digestList(List<String> digestList) {
            this.digestList = digestList;
            return this;
        }

        public Builder lastSeen(LocalDateTime lastSeen) {
            this.lastSeen = lastSeen;
            return this;
        }

        public Builder firstSeen(LocalDateTime firstSeen) {
            this.firstSeen = firstSeen;
            return this;
        }

        public Builder lockTime(BigDecimal lockTime) {
            this.lockTime = lockTime;
            return this;
        }

        public Builder rowsAffected(long rowsAffected) {
            this.rowsAffected = rowsAffected;
            return this;
        }

        public Builder averageRowsAffected(long averageRowsAffected) {
            this.averageRowsAffected = averageRowsAffected;
            return this;
        }

        public RuntimeMetrics build() {
            return new RuntimeMetrics(fullTableScan,
                                      executed,
                                      errors,
                                      warnings,
                                      totalTime,
                                      maxTime,
                                      averageTime,
                                      rowsSent,
                                      averageRowsSent,
                                      rowsScanned,
                                      averageRowsScanned,
                                      tempTables,
                                      tempDiskTables,
                                      rowsSorted,
                                      sortMergePasses,
                                      digest,
                                      digestList,
                                      lastSeen,
                                      firstSeen,
                                      lockTime,
                                      rowsAffected,
                                      averageRowsAffected);
        }

        public Builder fromPerformanceSchemaRow(MeteredSql row) {
            return fullTableScan("*".equals(row.getFullTableScan()))
                .executed(row.getExecuted())
                .errors(row.getErrors())
                .warnings(row.getWarnings())
                .totalTime(row.getTotalTime())
                .maxTime(row.getMaxTime())
                .averageTime(row.getAverageTime())
                .rowsSent(row.getRowsSent())
                .averageRowsSent(row.getAverageRowsSent())
                .rowsScanned(row.getRowsScanned())
                .averageRowsScanned(row.getAverageRowsScanned())
                .tempTables(row.getTempTables())
                .tempDiskTables(row.getTempDiskTables())
                .rowsSorted(row.getRowsSorted())
                .sortMergePasses(row.getSortMergePasses())
                .lastSeen(row.getLastSeen())
                .firstSeen(row.getFirstSeen())
                .lockTime(row.getLockTime())
                .rowsAffected(row.getRowsAffected())
                .averageRowsAffected(row.getAverageRowsAffected())
                .digest(row.getDigest());
        }
    }
}
