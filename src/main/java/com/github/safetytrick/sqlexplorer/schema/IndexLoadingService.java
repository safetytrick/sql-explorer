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

    /*
     * <P>Each index column description has the following columns:
     *  <OL>
     *  <LI><B>TABLE_CAT</B> String {@code =>} table catalog (may be <code>null</code>)
     *  <LI><B>TABLE_SCHEM</B> String {@code =>} table schema (may be <code>null</code>)
     *  <LI><B>TABLE_NAME</B> String {@code =>} table name
     *  <LI><B>NON_UNIQUE</B> boolean {@code =>} Can index values be non-unique.
     *      false when TYPE is tableIndexStatistic
     *  <LI><B>INDEX_QUALIFIER</B> String {@code =>} index catalog (may be <code>null</code>);
     *      <code>null</code> when TYPE is tableIndexStatistic
     *  <LI><B>INDEX_NAME</B> String {@code =>} index name; <code>null</code> when TYPE is
     *      tableIndexStatistic
     *  <LI><B>TYPE</B> short {@code =>} index type:
     *      <UL>
     *      <LI> tableIndexStatistic - this identifies table statistics that are
     *           returned in conjunction with a table's index descriptions
     *      <LI> tableIndexClustered - this is a clustered index
     *      <LI> tableIndexHashed - this is a hashed index
     *      <LI> tableIndexOther - this is some other style of index
     *      </UL>
     *  <LI><B>ORDINAL_POSITION</B> short {@code =>} column sequence number
     *      within index; zero when TYPE is tableIndexStatistic
     *  <LI><B>COLUMN_NAME</B> String {@code =>} column name; <code>null</code> when TYPE is
     *      tableIndexStatistic
     *  <LI><B>ASC_OR_DESC</B> String {@code =>} column sort sequence, "A" {@code =>} ascending,
     *      "D" {@code =>} descending, may be <code>null</code> if sort sequence is not supported;
     *      <code>null</code> when TYPE is tableIndexStatistic
     *  <LI><B>CARDINALITY</B> long {@code =>} When TYPE is tableIndexStatistic, then
     *      this is the number of rows in the table; otherwise, it is the
     *      number of unique values in the index.
     *  <LI><B>PAGES</B> long {@code =>} When TYPE is  tableIndexStatistic then
     *      this is the number of pages used for the table, otherwise it
     *      is the number of pages used for the current index.
     *  <LI><B>FILTER_CONDITION</B> String {@code =>} Filter condition, if any.
     */
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

    /**
     * Groups the (table, index-name, column-sequence)-ordered rows into one
     * {@link TableIndex} per index, keyed by the owning table.
     *
     * <p>The result set holds one row <em>per column of an index</em>, so rows
     * accumulate into a builder that is flushed when the index changes. An
     * index changes for two reasons and both have to be handled: the index
     * name differs, <em>or</em> the table does. Handling only the first is the
     * bug this method was extracted to pin — the pending builder was neither
     * flushed into the table that owned it nor cleared at the boundary, so
     * every table silently lost its last index to the next one, and where the
     * two tables' boundary indexes shared a name (`PRIMARY`, almost always)
     * the next table's columns were appended to the previous table's index
     * instead, surfacing as a duplicate.
     */
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
                // flush into the table being left, not the one being entered
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
                    // mysql sends over schema in the catalog column
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
            // close the statement when the returned ResultSet is closed by the caller's
            // try-with-resources, so the statement is not orphaned.
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
