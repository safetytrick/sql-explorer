package extensions.com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.evidence.source.MeteredSql;
import manifold.ext.rt.api.Extension;
import manifold.ext.rt.api.This;

@Extension
public class RuntimeMetricsExtension {

    @Extension
    public static RuntimeMetrics merged(List<RuntimeMetrics> toMerge) {
        if (toMerge == null || toMerge.isEmpty()) {
            throw new IllegalArgumentException("toMerge must contain at least one metric");
        }
        if (toMerge.size() == 1) {
            return toMerge.getFirst();
        }
        BigDecimal totalTime = toMerge.stream().map(RuntimeMetrics::getTotalTime).reduce(BigDecimal::add).orElse(BigDecimal.ZERO);
        long executed = toMerge.stream().mapToLong(RuntimeMetrics::getExecuted).sum();
        long rowsSent = toMerge.stream().mapToLong(RuntimeMetrics::getRowsSent).sum();
        long rowsScanned = toMerge.stream().mapToLong(RuntimeMetrics::getRowsScanned).sum();
        long rowsAffected = toMerge.stream().mapToLong(RuntimeMetrics::getRowsAffected).sum();
        return RuntimeMetrics.newBuilder().setFullTableScan(toMerge.stream().anyMatch(RuntimeMetrics::getFullTableScan))
            .setExecuted(executed)
            .setErrors(toMerge.stream().mapToLong(RuntimeMetrics::getErrors).sum())
            .setWarnings(toMerge.stream().mapToLong(RuntimeMetrics::getWarnings).sum())
            .setTotalTime(totalTime)
            .setMaxTime(toMerge.stream().map(RuntimeMetrics::getMaxTime).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO))
            .setAverageTime(totalTime.divide(BigDecimal.valueOf(executed), RoundingMode.HALF_UP))
            .setRowsSent(rowsSent)
            .setAverageRowsSent(rowsSent / executed)
            .setRowsScanned(rowsScanned)
            .setAverageRowsScanned(rowsScanned / executed)
            .setTempTables(toMerge.stream().mapToLong(RuntimeMetrics::getTempTables).sum())
            .setTempDiskTables(toMerge.stream().mapToLong(RuntimeMetrics::getTempDiskTables).sum())
            .setRowsSorted(toMerge.stream().mapToLong(RuntimeMetrics::getRowsSorted).sum())
            .setSortMergePasses(toMerge.stream().mapToLong(RuntimeMetrics::getSortMergePasses).sum())
            .setLastSeen(toMerge.stream().map(RuntimeMetrics::getLastSeen).max(Comparator.naturalOrder()).orElse(null))
            .setFirstSeen(toMerge.stream().map(RuntimeMetrics::getFirstSeen).min(Comparator.naturalOrder()).orElse(null))
            .setLockTime(toMerge.stream().map(RuntimeMetrics::getLockTime).reduce(BigDecimal::add).orElse(BigDecimal.ZERO))
            .setRowsAffected(rowsAffected)
            .setAverageRowsAffected(rowsAffected / executed)
            .setDigest(toMerge.getFirst().getDigest())
            .setDigestList(toMerge.stream().flatMap(metrics -> metrics.getDigestList().stream()).collect(Collectors.toList()))
            .build();
    }

    public static class Builder {
        public static RuntimeMetrics completeBuild(@This RuntimeMetrics.Builder thiz) {
            if (thiz.getDigestList() == null || thiz.getDigestList().isEmpty()) {
                thiz.setDigestList(List.of(thiz.getDigest()));
            }
            return thiz.build();
        }

        public static RuntimeMetrics.Builder fromPerformanceSchemaRow(@This RuntimeMetrics.Builder thiz, MeteredSql row) {
            return thiz.setFullTableScan("*".equals(row.getFullTableScan()))
                .setExecuted(row.getExecuted())
                .setErrors(row.getErrors())
                .setWarnings(row.getWarnings())
                .setTotalTime(row.getTotalTime())
                .setMaxTime(row.getMaxTime())
                .setAverageTime(row.getAverageTime())
                .setRowsSent(row.getRowsSent())
                .setAverageRowsSent(row.getAverageRowsSent())
                .setRowsScanned(row.getRowsScanned())
                .setAverageRowsScanned(row.getAverageRowsScanned())
                .setTempTables(row.getTempTables())
                .setTempDiskTables(row.getTempDiskTables())
                .setRowsSorted(row.getRowsSorted())
                .setSortMergePasses(row.getSortMergePasses())
                .setLastSeen(row.getLastSeen())
                .setFirstSeen(row.getFirstSeen())
                .setLockTime(row.getLockTime())
                .setRowsAffected(row.getRowsAffected())
                .setAverageRowsAffected(row.getAverageRowsAffected())
                .setDigest(row.getDigest());
        }
    }
}
