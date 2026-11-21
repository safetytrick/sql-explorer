package com.github.safetytrick.sqlexplorer.schema;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * <b>{@code rowsEstimate} is an estimate.</b> For InnoDB, {@code TABLE_ROWS}
 * comes from sampled index statistics and can be off by a wide margin; it is
 * never a {@code COUNT(*)}. MySQL 8 also caches these figures for
 * {@code information_schema_stats_expiry} seconds (a day by default), so
 * {@code readAt} is when they were read, not when the server computed them.
 */
public record TableStats(String server, Instant readAt, Long rowsEstimate, Long dataLength, Long indexLength,
                         Long dataFree, Long avgRowLength, Long autoIncrement, String rowFormat, String engine,
                         Instant createTime, Instant updateTime, String tableCollation) {

    public Long totalLength() {
        if (dataLength == null && indexLength == null) {
            return null;
        }
        return (dataLength == null ? 0 : dataLength) + (indexLength == null ? 0 : indexLength);
    }

    public static Long totalRowsEstimate(List<TableStats> stats) {
        return sum(stats, TableStats::rowsEstimate);
    }

    public static Long totalSize(List<TableStats> stats) {
        return sum(stats, TableStats::totalLength);
    }

    public static Long totalDataLength(List<TableStats> stats) {
        return sum(stats, TableStats::dataLength);
    }

    public static Long totalIndexLength(List<TableStats> stats) {
        return sum(stats, TableStats::indexLength);
    }

    private static Long sum(List<TableStats> stats, java.util.function.Function<TableStats, Long> figure) {
        if (stats == null) {
            return null;
        }
        List<Long> figures = stats.stream().map(figure).filter(Objects::nonNull).toList();
        return figures.isEmpty() ? null : figures.stream().mapToLong(Long::longValue).sum();
    }
}
