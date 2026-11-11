package com.github.safetytrick.sqlexplorer.schema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * Reads every table's size and row estimate from {@code information_schema.TABLES}
 * in one query (sqlx-n5j.2).
 *
 * <p><b>Rides along with the schema check; never a read of its own.</b> It runs
 * only where the schema loader already holds a live connection to the server
 * — right after the fingerprint, on every load and pull — so it adds one
 * metadata query to a read that was happening anyway, and nothing at all to
 * an environment served from its cache, which never reaches this code
 * ({@code DatabaseServer.getDataSource} refuses first).
 *
 * <p>It reads the server's bookkeeping, never table data: MySQL 8 answers
 * from {@code mysql.table_stats}, refreshed at most every
 * {@code information_schema_stats_expiry} seconds.
 */
@Service
public class TableStatsService {

    static final String TABLE_STATS = """
        SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH, DATA_FREE, AVG_ROW_LENGTH,
               AUTO_INCREMENT, ROW_FORMAT, ENGINE, CREATE_TIME, UPDATE_TIME, TABLE_COLLATION
          FROM information_schema.TABLES
         WHERE TABLE_SCHEMA NOT IN ('mysql', 'sys', 'performance_schema', 'information_schema')
           AND TABLE_TYPE = 'BASE TABLE'
        """;

    /**
     * @return each base table's figures, keyed as {@link SchemaFingerprint#key}
     *         keys a table: lowercase {@code schema.table}
     */
    public Map<String, TableStats> read(DataSource dataSource, String server) {
        Instant readAt = Instant.now();
        Map<String, TableStats> stats = new HashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(TABLE_STATS);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                stats.put(SchemaFingerprint.key(rows.getString("TABLE_SCHEMA"), rows.getString("TABLE_NAME")),
                    new TableStats(server, readAt,
                        longOrNull(rows, "TABLE_ROWS"),
                        longOrNull(rows, "DATA_LENGTH"),
                        longOrNull(rows, "INDEX_LENGTH"),
                        longOrNull(rows, "DATA_FREE"),
                        longOrNull(rows, "AVG_ROW_LENGTH"),
                        longOrNull(rows, "AUTO_INCREMENT"),
                        rows.getString("ROW_FORMAT"),
                        rows.getString("ENGINE"),
                        instantOrNull(rows, "CREATE_TIME"),
                        instantOrNull(rows, "UPDATE_TIME"),
                        rows.getString("TABLE_COLLATION")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read table statistics: " + e.getMessage(), e);
        }
        return stats;
    }

    private static Long longOrNull(ResultSet rows, String column) throws SQLException {
        // BIGINT UNSIGNED: getLong overflows only past 2^63, which no table reaches
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private static Instant instantOrNull(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
