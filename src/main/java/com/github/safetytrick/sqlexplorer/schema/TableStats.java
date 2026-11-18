package com.github.safetytrick.sqlexplorer.schema;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One server's {@code information_schema.TABLES} figures for a table, as of
 * {@code readAt} (sqlx-n5j.2). Every figure may be null: a view has none, and
 * a server may report none.
 *
 * <p><b>{@code rowsEstimate} is an estimate.</b> For InnoDB, {@code TABLE_ROWS}
 * comes from sampled index statistics and can be off by a wide margin; it is
 * never a {@code COUNT(*)}. MySQL 8 also caches these figures for
 * {@code information_schema_stats_expiry} seconds (a day by default), so
 * {@code readAt} is when they were read, not when the server computed them.
 *
 * @param server         the server that reported them
 * @param readAt         when they were read
 * @param rowsEstimate   {@code TABLE_ROWS}
 * @param dataLength     {@code DATA_LENGTH}, bytes
 * @param indexLength    {@code INDEX_LENGTH}, bytes
 * @param dataFree       {@code DATA_FREE}, bytes allocated but unused
 * @param avgRowLength   {@code AVG_ROW_LENGTH}, bytes
 * @param autoIncrement  {@code AUTO_INCREMENT}: the next value; null without one
 * @param rowFormat      {@code ROW_FORMAT}
 * @param engine         {@code ENGINE}
 * @param createTime     {@code CREATE_TIME}
 * @param updateTime     {@code UPDATE_TIME}; often null for InnoDB
 * @param tableCollation {@code TABLE_COLLATION}
 */
public record TableStats(String server, Instant readAt, Long rowsEstimate, Long dataLength, Long indexLength,
                         Long dataFree, Long avgRowLength, Long autoIncrement, String rowFormat, String engine,
                         Instant createTime, Instant updateTime, String tableCollation) {

    /** Data and index together, the table's footprint on disk; null when the server reported neither. */
    public Long totalLength() {
        if (dataLength == null && indexLength == null) {
            return null;
        }
        return (dataLength == null ? 0 : dataLength) + (indexLength == null ? 0 : indexLength);
    }

    /**
     * Rows across the servers that report the table — each cluster (shard)
     * carries its own rows, so the figures add up. Null when none reports one.
     */
    public static Long totalRowsEstimate(List<TableStats> stats) {
        return sum(stats, TableStats::rowsEstimate);
    }

    /** Data plus index bytes across the servers that report the table; null when none does. */
    public static Long totalSize(List<TableStats> stats) {
        return sum(stats, TableStats::totalLength);
    }

    /** {@code DATA_LENGTH} across the servers that report the table; null when none does. */
    public static Long totalDataLength(List<TableStats> stats) {
        return sum(stats, TableStats::dataLength);
    }

    /** {@code INDEX_LENGTH} across the servers that report the table; null when none does. */
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
