package com.github.safetytrick.sqlexplorer.schema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Service;

@DependsOnDatabaseInitialization
@Service
public class IndexLoadingService {
    private static final Logger log = LoggerFactory.getLogger(IndexLoadingService.class);

    private final DataSource metadataDataSource;
    private final Map<TableReference, List<TableIndex>> indexes = new ConcurrentHashMap<>();

    public IndexLoadingService(@Named(MetadataDataSource.NAME) DataSource metadataDataSource) {
        this.metadataDataSource = metadataDataSource;
    }

    public List<TableIndex> getIndexes(TableReference tableReference) {
        return indexes.getOrDefault(tableReference.normalized(), Collections.emptyList());
    }

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
    @PostConstruct
    private void initialize() {
        log.info("Reading Indexes from database...");
        long start = System.nanoTime();
        try (Connection connection = metadataDataSource.getConnection()) {
            TableReference table = null;
            List<TableIndex> indices = new ArrayList<>();
            try (ResultSet indexInfo = connection.getMetaData().getIndexInfo(null, null, null, false, false)) {
                TableIndex.Builder builder = null;
                String prevIndexName = null;
                while (indexInfo.next()) {
                    String schema = indexInfo.getString("TABLE_CAT");
                    String tableName = indexInfo.getString("TABLE_NAME");
                    if (table == null || !(
                        table.getNormalizedSchemaName().equalsIgnoreCase(schema) &&
                            table.getNormalizedTableName().equalsIgnoreCase(tableName)
                    )) {
                        if (table != null) {
                            indexes.put(table, indices);
                            indices = new ArrayList<>();
                        }
                        table = TableReference.newTableReference(schema, tableName).normalized();
                    }
                    String indexName = indexInfo.getString("INDEX_NAME");
                    if (indexName == null) {
                        continue;
                    }

                    if (builder == null || !Objects.equals(prevIndexName, indexName)) {
                        if (builder != null) {
                            indices.add(builder.build());
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
                    short ordinalPosition = indexInfo.getShort("ORDINAL_POSITION");
                    String columnName = indexInfo.getString("COLUMN_NAME");

                    builder.addColumn(ordinalPosition, columnName);

                }
                if (builder != null) {
                    indices.add(builder.build());
                }
                if (table != null) {
                    indexes.put(table, indices);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        log.info("Completed read of indexes in {}", Duration.ofNanos(System.nanoTime() - start));

    }
}
