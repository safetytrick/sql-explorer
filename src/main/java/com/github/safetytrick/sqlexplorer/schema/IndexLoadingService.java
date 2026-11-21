package com.github.safetytrick.sqlexplorer.schema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IndexLoadingService {
    private static final Logger log = LoggerFactory.getLogger(IndexLoadingService.class);

    IndicesFetcher getIndicesFetcher(DataSource dataSource) {
        Map<TableReference, List<TableIndex>> indices;
        log.info("Reading Indexes from database...");
        long start = System.nanoTime();
        try (Connection connection = dataSource.getConnection();
             ResultSet indexInfo = getIndexInfo(connection)) {
            indices = readIndices(indexInfo);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        log.info("Completed read of indexes in {}", Duration.ofNanos(System.nanoTime() - start));
        return table -> indices.getOrDefault(table.normalized(), Collections.emptyList());
    }

    Map<TableReference, List<TableIndex>> readIndices(ResultSet indexInfo) throws SQLException {
        Map<TableReference, List<TableIndex>> indices = new HashMap<>();
        TableReference table = null;
        List<TableIndex> tableIndices = new ArrayList<>();
        TableIndex.Builder builder = null;
        String prevIndexName = null;

        while (indexInfo.next()) {
            String schema = indexInfo.getString("TABLE_CAT");
            String tableName = indexInfo.getString("TABLE_NAME");
            boolean newTable = table == null || !(
                table.getNormalizedSchemaName().equalsIgnoreCase(schema) &&
                    table.getNormalizedTableName().equalsIgnoreCase(tableName)
            );
            if (newTable) {
                if (builder != null) {
                    tableIndices.add(builder.build());
                    builder = null;
                }
                if (table != null) {
                    indices.put(table, tableIndices);
                    tableIndices = new ArrayList<>();
                }
                prevIndexName = null;
                table = TableReference.newTableReference(schema, tableName).normalized();
            }

            String indexName = indexInfo.getString("INDEX_NAME");
            if (indexName == null) {
                continue;
            }

            if (builder == null || !Objects.equals(prevIndexName, indexName)) {
                if (builder != null) {
                    tableIndices.add(builder.build());
                }
                prevIndexName = indexName;

                builder = TableIndex.builder()
                    .name(indexName)
                    .schema(indexInfo.getString("TABLE_CAT"))
                    .unique(!indexInfo.getBoolean("NON_UNIQUE"))
                    .identifier(indexInfo.getString("INDEX_QUALIFIER"))
                    .indexType(IndexType.fromIndex(indexInfo.getShort("TYPE")))
                    .direction(IndexDirection.fromKey(indexInfo.getString("ASC_OR_DESC")))
                    .cardinality(indexInfo.getLong("CARDINALITY"))
                    .pages(indexInfo.getLong("PAGES"))
                    .filterCondition(indexInfo.getString("FILTER_CONDITION"));
            }
            builder.addColumn(indexInfo.getShort("ORDINAL_POSITION"), indexInfo.getString("COLUMN_NAME"));
        }

        if (builder != null) {
            tableIndices.add(builder.build());
        }
        if (table != null) {
            indices.put(table, tableIndices);
        }
        return indices;
    }

    /**
     * DatabaseMetaData.getIndexInfo requires a table name as of mysql-connector-j 9.x.
     * Query INFORMATION_SCHEMA.STATISTICS directly (the same source the driver uses)
     * to keep the bulk all-tables load, emulating the getIndexInfo result columns.
     */
    private ResultSet getIndexInfo(Connection connection) throws SQLException {
        String sql = """
            SELECT TABLE_SCHEMA          AS TABLE_CAT,
                NULL                     AS TABLE_SCHEM,
                TABLE_NAME,
                NON_UNIQUE,
                NULL                     AS INDEX_QUALIFIER,
                INDEX_NAME,
                CASE
                    WHEN INDEX_TYPE = 'HASH' THEN 2
                    ELSE 3 END           AS TYPE,
                SEQ_IN_INDEX             AS ORDINAL_POSITION,
                COLUMN_NAME,
                COLLATION                AS ASC_OR_DESC,
                CARDINALITY,
                0                        AS PAGES,
                NULL                     AS FILTER_CONDITION
            FROM INFORMATION_SCHEMA.STATISTICS
            ORDER BY TABLE_SCHEMA, TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX
            """;

        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.closeOnCompletion();
            if (statement.getMaxRows() != 0) {
                statement.setMaxRows(0);
            }

            return statement.executeQuery();
        } catch (SQLException e) {
            // no ResultSet was produced, so closeOnCompletion won't fire; close here.
            statement.close();
            throw e;
        }
    }

    interface IndicesFetcher {
        List<TableIndex> getIndices(TableReference tableReference);
    }
}
