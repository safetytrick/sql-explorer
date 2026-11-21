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

@Service
public class TableStatsService {

    static final String TABLE_STATS = """
        SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH, DATA_FREE, AVG_ROW_LENGTH,
               AUTO_INCREMENT, ROW_FORMAT, ENGINE, CREATE_TIME, UPDATE_TIME, TABLE_COLLATION
          FROM information_schema.TABLES
         WHERE TABLE_SCHEMA NOT IN ('mysql', 'sys', 'performance_schema', 'information_schema')
           AND TABLE_TYPE = 'BASE TABLE'
        """;

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
