package com.github.safetytrick.sqlexplorer.schema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ColumnEncodingService {
    private static final Logger log = LoggerFactory.getLogger(ColumnEncodingService.class);

    ColumnEncodingFetcher getColumnEncodingFetcher(DataSource dataSource) {
        Map<ColumnReference, ColumnEncoding> columnEncodings = new HashMap<>();
        log.info("Reading ColumnEncodings from database...");
        long start = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            /*
            select TABLE_SCHEMA,
               TABLE_NAME,
               COLUMN_NAME,
               ORDINAL_POSITION,
               COLUMN_DEFAULT,
               IS_NULLABLE,
               DATA_TYPE,
               CHARACTER_MAXIMUM_LENGTH,
               CHARACTER_OCTET_LENGTH,
               NUMERIC_PRECISION,
               NUMERIC_SCALE,
               DATETIME_PRECISION,
               CHARACTER_SET_NAME,
               COLLATION_NAME,
               COLUMN_KEY,
               EXTRA,
               COLUMN_COMMENT
            from information_schema.COLUMNS
            where table_name = ?;
            */
            String sql = """
            SELECT TABLE_SCHEMA, \
               TABLE_NAME, \
               COLUMN_NAME, \
               CHARACTER_SET_NAME, \
               COLLATION_NAME \
             FROM information_schema.COLUMNS \
             ORDER BY TABLE_SCHEMA, TABLE_NAME; \
            """;

            try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
                TableReference table = null;
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    while (resultSet.next()) {
                        String schema = resultSet.getString("TABLE_SCHEMA");
                        String tableName = resultSet.getString("TABLE_NAME");
                        if (table == null || !(
                            table.getNormalizedSchemaName().equalsIgnoreCase(schema) &&
                            table.getNormalizedTableName().equalsIgnoreCase(tableName)
                        )) {
                            table = TableReference.newTableReference(schema, tableName).normalized();
                        }
                        var column = ColumnReference.newColumnReference(table,
                            resultSet.getString("COLUMN_NAME")).normalized();

                        var columnEncoding = new ColumnEncoding(
                            resultSet.getString("CHARACTER_SET_NAME"),
                            resultSet.getString("COLLATION_NAME")
                        );
                        columnEncodings.put(column, columnEncoding);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        log.info("Completed read of column encodings in {}", Duration.ofNanos(System.nanoTime() - start));
        return column -> columnEncodings.get(column.normalized());
    }

    public record ColumnEncoding(String characterSet, String collation) { }

    interface ColumnEncodingFetcher {
        ColumnEncoding getEncoding(ColumnReference column);
    }
}
