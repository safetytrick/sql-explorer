package com.github.safetytrick.sqlexplorer.schema;

import javax.annotation.PostConstruct;
import javax.inject.Named;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.github.safetytrick.sqlexplorer.model.ColumnReference;
import com.github.safetytrick.sqlexplorer.model.TableReference;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SchemaLoadingService {

    private static final Logger log = LoggerFactory.getLogger(SchemaLoadingService.class);

    private final DataSource metadataDataSource;

    // todo remove this global scope and reference this as an environment
    private volatile List<TableStructure> tableStructures;
    private final Map<TableReference, TableStructure> byTableReference = new ConcurrentHashMap<>();
    private final ListMultimap<String, TableStructure> byTableName = ArrayListMultimap.create();

    public SchemaLoadingService(@Named(MetadataDataSource.NAME) DataSource metadataDataSource) {
        this.metadataDataSource = metadataDataSource;
    }

    private Connection getConnection() throws SQLException {
        return metadataDataSource.getConnection();
    }

    /*
     *  <OL>
     *  <LI><B>TABLE_CAT</B> String {@code =>} table catalog (may be <code>null</code>)
     *  <LI><B>TABLE_SCHEM</B> String {@code =>} table schema (may be <code>null</code>)
     *  <LI><B>TABLE_NAME</B> String {@code =>} table name
     *  <LI><B>TABLE_TYPE</B> String {@code =>} table type.  Typical types are "TABLE",
     *                  "VIEW", "SYSTEM TABLE", "GLOBAL TEMPORARY",
     *                  "LOCAL TEMPORARY", "ALIAS", "SYNONYM".
     *  <LI><B>REMARKS</B> String {@code =>} explanatory comment on the table (may be {@code null})
     *  <LI><B>TYPE_CAT</B> String {@code =>} the types catalog (may be <code>null</code>)
     *  <LI><B>TYPE_SCHEM</B> String {@code =>} the types schema (may be <code>null</code>)
     *  <LI><B>TYPE_NAME</B> String {@code =>} type name (may be <code>null</code>)
     *  <LI><B>SELF_REFERENCING_COL_NAME</B> String {@code =>} name of the designated
     *                  "identifier" column of a typed table (may be <code>null</code>)
     *  <LI><B>REF_GENERATION</B> String {@code =>} specifies how values in
     *                  SELF_REFERENCING_COL_NAME are created. Values are
     *                  "SYSTEM", "USER", "DERIVED". (may be <code>null</code>)
     *  </OL>
     */
    public List<TableStructure> getTables() {
        return tableStructures;
    }

    public TableStructure getTable(TableReference table) {
        Objects.requireNonNull(table);

        return byTableReference.get(table);
    }


    public List<TableStructure> getAllByTableName(String table) {
        return byTableName.get(table.toLowerCase());
    }

    @PostConstruct
    private void initializeTableStructures() {
        try (Connection connection = getConnection()) {
            try (ResultSet tables = connection.getMetaData().getTables(null, null, null, null)) {
                List<TableStructure> tableStructures = new ArrayList<>();
                while (tables.next()) {
                    // the jdbc driver sends the schema column over in the catalog field
                    String schema = tables.getString("TABLE_CAT");

                    String tableName = tables.getString("TABLE_NAME");
                    if (schema == null) {
                        log.warn("null schema for table {}", tableName);
                    }
                    String tableType = tables.getString("TABLE_TYPE");
                    String remarks = tables.getString("REMARKS");
                    String typesCatalog = tables.getString("TYPE_CAT");
                    String typesSchema = tables.getString("TYPE_SCHEM");
                    String typeName = tables.getString("TYPE_NAME");
                    String selfReferencingColumnName = tables.getString("SELF_REFERENCING_COL_NAME");
                    String refGeneration = tables.getString("REF_GENERATION");
                    String create = getCreateStatement(schema, tableName);

                    TableReference table = new TableReference(schema, tableName);
                    TableStructure structure = TableStructure.builder()
                        .schema(schema)
                        .name(tableName)
                        .create(create)
                        .type(tableType)
                        .remarks(remarks)
                        .foreignKeys(
                            getForeignKeys(connection, table)
                        )
                        .columns(
                            getTableColumns(connection, table)
                        )
                        .indices(
                            getIndexesForTable(connection, table)
                        ).build();

                    tableStructures.add(structure);
                    addTableStructure(table, structure);
                }
                this.tableStructures = tableStructures;
            }
        } catch (SQLException e) {
            tableStructures = Collections.emptyList();
            throw new IllegalArgumentException(e);
        }

    }

    /*
     *  PKTABLE_CAT String => primary key table catalog being imported (may be null)
     *  PKTABLE_SCHEM String => primary key table schema being imported (may be null)
     *  PKTABLE_NAME String => primary key table name being imported
     *  PKCOLUMN_NAME String => primary key column name being imported
     *  FKTABLE_CAT String => foreign key table catalog (may be null)
     *  FKTABLE_SCHEM String => foreign key table schema (may be null)
     *  FKTABLE_NAME String => foreign key table name
     *  FKCOLUMN_NAME String => foreign key column name
     *  KEY_SEQ short => sequence number within a foreign key( a value of 1 represents the first column of the foreign key, a value of 2 would represent the second column within the foreign key).
     *  UPDATE_RULE short => What happens to a foreign key when the primary key is updated:
     *      importedNoAction - do not allow update of primary key if it has been imported
     *      importedKeyCascade - change imported key to agree with primary key update
     *      importedKeySetNull - change imported key to NULL if its primary key has been updated
     *      importedKeySetDefault - change imported key to default values if its primary key has been updated
     *      importedKeyRestrict - same as importedKeyNoAction (for ODBC 2.x compatibility)
     *  DELETE_RULE short => What happens to the foreign key when primary is deleted.
     *      importedKeyNoAction - do not allow delete of primary key if it has been imported
     *      importedKeyCascade - delete rows that import a deleted key
     *      importedKeySetNull - change imported key to NULL if its primary key has been deleted
     *      importedKeyRestrict - same as importedKeyNoAction (for ODBC 2.x compatibility)
     *      importedKeySetDefault - change imported key to default if its primary key has been deleted
     *  FK_NAME String => foreign key name (may be null)
     *  PK_NAME String => primary key name (may be null)
     *  DEFERRABILITY short => can the evaluation of foreign key constraints be deferred until commit
     *      importedKeyInitiallyDeferred - see SQL92 for definition
     *      importedKeyInitiallyImmediate - see SQL92 for definition
     *      importedKeyNotDeferrable - see SQL92 for definition
     */
    private List<ForeignKey> getForeignKeys(Connection connection, TableReference table) throws SQLException {
        ForeignKey.Builder builder = null;
        List<ForeignKey> result = new ArrayList<>();
        try (ResultSet keys = connection.getMetaData()
            .getImportedKeys(table.getSchema(), table.getSchema(), table.getTableName())) {

            while (keys.next()) {
                int sequence = keys.getInt("KEY_SEQ");

                if (sequence == 1) {
                    if (builder != null) {
                        result.add(builder.build());
                    }
                    builder = new ForeignKey.Builder();
                }
                if (builder == null) {
                    throw new NullPointerException("invalid key sequence");
                }

                // the jdbc driver sends the schema column over in the catalog field
                String fromSchema = keys.getString("FKTABLE_CAT");
                String fromTable = keys.getString("FKTABLE_NAME");
                String fromColumn = keys.getString("FKCOLUMN_NAME");
                String toSchema = keys.getString("PKTABLE_CAT");
                String toTable = keys.getString("PKTABLE_NAME");
                String toColumn = keys.getString("PKCOLUMN_NAME");
                builder.addFromAndTo(
                    ColumnReference.fromComponents(fromSchema, fromTable, fromColumn),
                    ColumnReference.fromComponents(toSchema, toTable, toColumn)
                );
            }
        }
        if (builder != null) {
            result.add(builder.build());
        }
        return result;
    }

    private String getCreateStatement(String schema, String tableName) {
        try (Connection connection = getConnection()) {
            try (Statement statement = connection.createStatement()) {
                try (ResultSet resultSet = statement.executeQuery("show create table `" + schema + "`.`" + tableName+"`")) {
                    resultSet.next();

                    return resultSet.getString(2);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void addTableStructure(TableReference reference, TableStructure structure) {
        byTableReference.put(reference, structure);
        byTableName.put(structure.name().toLowerCase(), structure);

    }

    /*
     * <P>Only column descriptions matching the catalog, schema, table
     * and column name criteria are returned.  They are ordered by
     * <code>TABLE_CAT</code>,<code>TABLE_SCHEM</code>,
     * <code>TABLE_NAME</code>, and <code>ORDINAL_POSITION</code>.
     *
     * <P>Each column description has the following columns:
     *  <OL>
     *  <LI><B>TABLE_CAT</B> String {@code =>} table catalog (may be <code>null</code>)
     *  <LI><B>TABLE_SCHEM</B> String {@code =>} table schema (may be <code>null</code>)
     *  <LI><B>TABLE_NAME</B> String {@code =>} table name
     *  <LI><B>COLUMN_NAME</B> String {@code =>} column name
     *  <LI><B>DATA_TYPE</B> int {@code =>} SQL type from java.sql.Types
     *  <LI><B>TYPE_NAME</B> String {@code =>} Data source dependent type name,
     *  for a UDT the type name is fully qualified
     *  <LI><B>COLUMN_SIZE</B> int {@code =>} column size.
     *  <LI><B>BUFFER_LENGTH</B> is not used.
     *  <LI><B>DECIMAL_DIGITS</B> int {@code =>} the number of fractional digits. Null is returned for data types where
     * DECIMAL_DIGITS is not applicable.
     *  <LI><B>NUM_PREC_RADIX</B> int {@code =>} Radix (typically either 10 or 2)
     *  <LI><B>NULLABLE</B> int {@code =>} is NULL allowed.
     *      <UL>
     *      <LI> columnNoNulls - might not allow <code>NULL</code> values
     *      <LI> columnNullable - definitely allows <code>NULL</code> values
     *      <LI> columnNullableUnknown - nullability unknown
     *      </UL>
     *  <LI><B>REMARKS</B> String {@code =>} comment describing column (may be <code>null</code>)
     *  <LI><B>COLUMN_DEF</B> String {@code =>} default value for the column, which should be interpreted as a string when the value is enclosed in single quotes (may be <code>null</code>)
     *  <LI><B>SQL_DATA_TYPE</B> int {@code =>} unused
     *  <LI><B>SQL_DATETIME_SUB</B> int {@code =>} unused
     *  <LI><B>CHAR_OCTET_LENGTH</B> int {@code =>} for char types the
     *       maximum number of bytes in the column
     *  <LI><B>ORDINAL_POSITION</B> int {@code =>} index of column in table
     *      (starting at 1)
     *  <LI><B>IS_NULLABLE</B> String  {@code =>} ISO rules are used to determine the nullability for a column.
     *       <UL>
     *       <LI> YES           --- if the column can include NULLs
     *       <LI> NO            --- if the column cannot include NULLs
     *       <LI> empty string  --- if the nullability for the
     * column is unknown
     *       </UL>
     *  <LI><B>SCOPE_CATALOG</B> String {@code =>} catalog of table that is the scope
     *      of a reference attribute (<code>null</code> if DATA_TYPE isn't REF)
     *  <LI><B>SCOPE_SCHEMA</B> String {@code =>} schema of table that is the scope
     *      of a reference attribute (<code>null</code> if the DATA_TYPE isn't REF)
     *  <LI><B>SCOPE_TABLE</B> String {@code =>} table name that this the scope
     *      of a reference attribute (<code>null</code> if the DATA_TYPE isn't REF)
     *  <LI><B>SOURCE_DATA_TYPE</B> short {@code =>} source type of a distinct type or user-generated
     *      Ref type, SQL type from java.sql.Types (<code>null</code> if DATA_TYPE
     *      isn't DISTINCT or user-generated REF)
     *   <LI><B>IS_AUTOINCREMENT</B> String  {@code =>} Indicates whether this column is auto incremented
     *       <UL>
     *       <LI> YES           --- if the column is auto incremented
     *       <LI> NO            --- if the column is not auto incremented
     *       <LI> empty string  --- if it cannot be determined whether the column is auto incremented
     *       </UL>
     *   <LI><B>IS_GENERATEDCOLUMN</B> String  {@code =>} Indicates whether this is a generated column
     *       <UL>
     *       <LI> YES           --- if this a generated column
     *       <LI> NO            --- if this not a generated column
     *       <LI> empty string  --- if it cannot be determined whether this is a generated column
     *       </UL>
     *  </OL>
     *
     * <p>The COLUMN_SIZE column specifies the column size for the given column.
     * For numeric data, this is the maximum precision.  For character data, this is the length in characters.
     * For datetime datatypes, this is the length in characters of the String representation (assuming the
     * maximum allowed precision of the fractional seconds component). For binary data, this is the length in bytes.  For the ROWID datatype,
     * this is the length in bytes. Null is returned for data types where the
     * column size is not applicable.
     */
    private List<TableColumn> getTableColumns(Connection connection, TableReference table) throws SQLException {
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
        String sql = "select TABLE_SCHEMA, " +
            "   TABLE_NAME, " +
            "   COLUMN_NAME, " +
            "   CHARACTER_SET_NAME, " +
            "   COLLATION_NAME " +
            " from information_schema.COLUMNS " +
            " where table_name = ? and table_schema = ?;";

        Map<String, List<String>> characterSetCollationMap = new HashMap<>();
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            preparedStatement.setString(1, table.getTableName());
            preparedStatement.setString(2, table.getSchema());

            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                while (resultSet.next()) {
                    characterSetCollationMap.put(resultSet.getString("COLUMN_NAME"),
                                                 Arrays.asList(resultSet.getString("CHARACTER_SET_NAME"),
                                                               resultSet.getString("COLLATION_NAME")));
                }
            }
        }

        try (ResultSet columns = connection.getMetaData().getColumns(table.getSchema(), table.getSchema(), table.getTableName(), null)) {
            List<TableColumn> tableColumns = new ArrayList<>();
            while (columns.next()) {
                // mysql sends over schema in the catalog column
                String schema = columns.getString("TABLE_CAT");
                String columnName = columns.getString("COLUMN_NAME");
                JDBCType type = JDBCType.valueOf(columns.getInt("DATA_TYPE"));
                String typeName = columns.getString("TYPE_NAME");
                int columnSize = columns.getInt("COLUMN_SIZE");
                int decimalDigits = columns.getInt("DECIMAL_DIGITS");
                int radix = columns.getInt("NUM_PREC_RADIX");
                // not sure why nullable is here twice
                int nullable = columns.getInt("NULLABLE");
                String remarks = columns.getString("REMARKS");
                String columnDefault = columns.getString("COLUMN_DEF");
                int octetLength = columns.getInt("CHAR_OCTET_LENGTH");
                int ordinal = columns.getInt("ORDINAL_POSITION");
                String isNullable = columns.getString("IS_NULLABLE");
                String isAutoIncrement = columns.getString("IS_AUTOINCREMENT");
                String isGenerated = columns.getString("IS_GENERATEDCOLUMN");

                List<String> characterSetCollation = characterSetCollationMap.getOrDefault(columnName, Arrays.asList(null, null));

                TableColumn tableColumn = TableColumn.builder()
                    .schema(schema)
                    .table(table.getTableName())
                    .columnName(columnName)
                    .type(type)
                    .typeName(typeName)
                    .columnSize(columnSize)
                    .decimalDigits(decimalDigits)
                    .radix(radix)
                    .isNullable(isNullable)
                    .remarks(remarks)
                    .columnDefault(columnDefault)
                    .octetLength(octetLength)
                    .ordinal(ordinal)
                    .isAutoIncrement(isAutoIncrement)
                    .isGenerated(isGenerated)
                    .characterSet(characterSetCollation.get(0))
                    .collation(characterSetCollation.get(1))
                    .build();

                tableColumns.add(tableColumn);

            }
            return tableColumns;
        }
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
    private List<TableIndex> getIndexesForTable(Connection connection, TableReference table) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet indexInfo = metaData.getIndexInfo(table.getSchema(), null, table.getTableName(), false, false)) {
            List<TableIndex> indices = new ArrayList<>();
            TableIndex.Builder builder = null;
            String prevIndexName = null;
            while (indexInfo.next()) {
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
            return indices;
        }
    }
}
