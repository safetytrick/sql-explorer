package com.github.safetytrick.sqlexplorer.schema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.JDBCType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Service;

@DependsOnDatabaseInitialization
@Service
public class TableColumnService {

    private static final Logger log = LoggerFactory.getLogger(TableColumnService.class);

    private final DataSource metadataDataSource;
    private final ColumnEncodingService columnEncodingService;

    private final Map<TableReference, List<TableColumn>> tableColumnsMap = new ConcurrentHashMap<>();

    public TableColumnService(@Named(MetadataDataSource.NAME) DataSource metadataDataSource, ColumnEncodingService columnEncodingService) {
        this.metadataDataSource = metadataDataSource;
        this.columnEncodingService = columnEncodingService;
    }

    public List<TableColumn> getColumns(TableReference table) {
        return tableColumnsMap.get(table.normalized());
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
    @PostConstruct
    private void initialize() {
        log.info("Reading Columns from database...");
        long start = System.nanoTime();
        try (Connection connection = metadataDataSource.getConnection()) {
            TableReference table = null;
            List<TableColumn> tableColumns = new ArrayList<>();
            try (ResultSet columns = connection.getMetaData().getColumns(null, null, null, null)) {
                while (columns.next()) {
                    String schema = columns.getString("TABLE_CAT");
                    String tableName = columns.getString("TABLE_NAME");
                    if (table == null || !(
                            table.getNormalizedSchemaName().equalsIgnoreCase(schema) &&
                            table.getNormalizedTableName().equalsIgnoreCase(tableName)
                    )) {
                        if (table != null) {
                            tableColumnsMap.put(table, tableColumns);
                            tableColumns = new ArrayList<>();
                        }
                        table = TableReference.newTableReference(schema, tableName).normalized();
                    }

                    // mysql sends over schema in the catalog column
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

                    ColumnEncodingService.ColumnEncoding encoding =
                        columnEncodingService.getEncoding(ColumnReference.newColumnReference(table, columnName));

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
                        .characterSet(encoding.characterSet())
                        .collation(encoding.collation())
                        .build();

                    tableColumns.add(tableColumn);
                }

            }
            if (table != null) {
                tableColumnsMap.put(table, tableColumns);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        log.info("Completed read of columns in {}", Duration.ofNanos(System.nanoTime() - start));
    }
}
