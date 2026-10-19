package com.github.safetytrick.sqlexplorer.schema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

/**
 * A cheap answer to "has this server's schema changed?", so a persisted
 * schema snapshot can be reused instead of re-reading every table
 * (docs/plans/cluster-schema-snapshots.md, sqlx-sqy.3).
 *
 * <p>One query, aggregated on the server: a hash per table for each kind of
 * metadata the schema loader reads. It never transfers the metadata itself,
 * which is what makes it cheap next to a full load.
 *
 * <p><b>What is hashed is exactly what the loader reads, and nothing that moves
 * without a schema change.</b> Index {@code CARDINALITY}, row counts and
 * {@code UPDATE_TIME} change with the data, so including any of them would
 * make every snapshot look stale. {@code VIEW_DEFINITION} is left out because
 * it is blank under the metadata-only grant profile ({@code REFERENCES, SHOW
 * VIEW}) — see docs/mysql-performance-schema.md — so it would hash a constant
 * there and the real text elsewhere.
 *
 * <p>Per table rather than one number, so that two fingerprints also say
 * <em>which</em> tables differ: that is what schema-drift reporting between
 * shards compares (sqlx-sqy.7).
 */
@Service
public class SchemaFingerprintService {

    /**
     * Each part is BIT_XOR over one row per column, index column, foreign-key
     * column or table. XOR is order-free, so the server need not sort; the rows
     * within a part are distinct by their own key, so no two cancel. COALESCE
     * keeps NULL apart from the empty string, which CONCAT_WS would otherwise
     * silently drop.
     *
     * <p>Foreign-key columns and their rules are two parts, not a join. Joined,
     * KEY_COLUMN_USAGE and REFERENTIAL_CONSTRAINTS took 5.6s on a production
     * primary with 142 constrained tables; apart, each takes as long as a
     * round trip. Both are keyed by the owning table, so nothing is lost.
     */
    static final String FINGERPRINT = """
        SELECT TABLE_SCHEMA, TABLE_NAME, 'table' AS part,
               BIT_XOR(CRC32(CONCAT_WS('|', COALESCE(TABLE_TYPE, '~'), COALESCE(TABLE_COMMENT, '~')))) AS hash
          FROM information_schema.TABLES
         WHERE TABLE_SCHEMA NOT IN ('mysql', 'sys', 'performance_schema', 'information_schema')
         GROUP BY TABLE_SCHEMA, TABLE_NAME
        UNION ALL
        SELECT TABLE_SCHEMA, TABLE_NAME, 'columns',
               BIT_XOR(CRC32(CONCAT_WS('|', COLUMN_NAME, ORDINAL_POSITION, COLUMN_TYPE, IS_NULLABLE,
                   COALESCE(COLUMN_DEFAULT, '~'), COALESCE(CHARACTER_SET_NAME, '~'),
                   COALESCE(COLLATION_NAME, '~'), COLUMN_KEY, EXTRA, COALESCE(COLUMN_COMMENT, '~'))))
          FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA NOT IN ('mysql', 'sys', 'performance_schema', 'information_schema')
         GROUP BY TABLE_SCHEMA, TABLE_NAME
        UNION ALL
        SELECT TABLE_SCHEMA, TABLE_NAME, 'indexes',
               BIT_XOR(CRC32(CONCAT_WS('|', INDEX_NAME, SEQ_IN_INDEX, COALESCE(COLUMN_NAME, '~'),
                   NON_UNIQUE, INDEX_TYPE, COALESCE(COLLATION, '~'), COALESCE(SUB_PART, '~'),
                   COALESCE(EXPRESSION, '~'))))
          FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA NOT IN ('mysql', 'sys', 'performance_schema', 'information_schema')
         GROUP BY TABLE_SCHEMA, TABLE_NAME
        UNION ALL
        SELECT TABLE_SCHEMA, TABLE_NAME, 'foreign-keys',
               BIT_XOR(CRC32(CONCAT_WS('|', CONSTRAINT_NAME, COLUMN_NAME, ORDINAL_POSITION,
                   REFERENCED_TABLE_SCHEMA, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME)))
          FROM information_schema.KEY_COLUMN_USAGE
         WHERE TABLE_SCHEMA NOT IN ('mysql', 'sys', 'performance_schema', 'information_schema')
           AND REFERENCED_TABLE_NAME IS NOT NULL
         GROUP BY TABLE_SCHEMA, TABLE_NAME
        UNION ALL
        SELECT CONSTRAINT_SCHEMA, TABLE_NAME, 'foreign-key-rules',
               BIT_XOR(CRC32(CONCAT_WS('|', CONSTRAINT_NAME, UPDATE_RULE, DELETE_RULE)))
          FROM information_schema.REFERENTIAL_CONSTRAINTS
         WHERE CONSTRAINT_SCHEMA NOT IN ('mysql', 'sys', 'performance_schema', 'information_schema')
         GROUP BY CONSTRAINT_SCHEMA, TABLE_NAME
        """;

    public SchemaFingerprint fingerprint(DataSource dataSource) {
        Map<String, Map<String, Long>> parts = new TreeMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FINGERPRINT);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String table = SchemaFingerprint.key(rows.getString(1), rows.getString(2));
                parts.computeIfAbsent(table, key -> new TreeMap<>()).put(rows.getString(3), rows.getLong(4));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not fingerprint the schema: " + e.getMessage(), e);
        }
        return SchemaFingerprint.of(parts);
    }
}
