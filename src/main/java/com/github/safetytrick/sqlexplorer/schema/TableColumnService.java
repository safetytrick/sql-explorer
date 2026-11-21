package com.github.safetytrick.sqlexplorer.schema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.JDBCType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TableColumnService {

    private static final Logger log = LoggerFactory.getLogger(TableColumnService.class);

    private final ColumnEncodingService columnEncodingService;

    public TableColumnService(ColumnEncodingService columnEncodingService) {
        this.columnEncodingService = columnEncodingService;
    }


    TableColumnsFetcher getColumnFetcher(DataSource dataSource) {
        Map<TableReference, List<TableColumn>> tableColumnsMap = new HashMap<>();
        ColumnEncodingService.ColumnEncodingFetcher columnEncodingFetcher = columnEncodingService.getColumnEncodingFetcher(dataSource);
        log.info("Reading Columns from database...");
        long start = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
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

                    String columnName = columns.getString("COLUMN_NAME");
                    JDBCType type = JDBCType.valueOf(columns.getInt("DATA_TYPE"));
                    String typeName = columns.getString("TYPE_NAME");
                    int columnSize = columns.getInt("COLUMN_SIZE");
                    int decimalDigits = columns.getInt("DECIMAL_DIGITS");
                    int radix = columns.getInt("NUM_PREC_RADIX");
                    int nullable = columns.getInt("NULLABLE");
                    String remarks = columns.getString("REMARKS");
                    String columnDefault = columns.getString("COLUMN_DEF");
                    int octetLength = columns.getInt("CHAR_OCTET_LENGTH");
                    int ordinal = columns.getInt("ORDINAL_POSITION");
                    String isNullable = columns.getString("IS_NULLABLE");
                    String isAutoIncrement = columns.getString("IS_AUTOINCREMENT");
                    String isGenerated = columns.getString("IS_GENERATEDCOLUMN");

                    ColumnEncodingService.ColumnEncoding encoding =
                        columnEncodingFetcher.getEncoding(ColumnReference.newColumnReference(table, columnName));

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
        return table -> tableColumnsMap.getOrDefault(table, Collections.emptyList());
    }

    @FunctionalInterface
    interface TableColumnsFetcher {
        List<TableColumn> getColumns(TableReference table);
    }
}
