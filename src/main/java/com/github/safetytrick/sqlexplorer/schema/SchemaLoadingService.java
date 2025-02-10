package com.github.safetytrick.sqlexplorer.schema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Service;

@DependsOnDatabaseInitialization
@Service
public class SchemaLoadingService {

    private static final Logger log = LoggerFactory.getLogger(SchemaLoadingService.class);

    private final DataSource metadataDataSource;
    private final TableColumnService tableColumnService;
    private final IndexLoadingService indexLoadingService;

    // todo remove this global scope and reference this as an environment
    private volatile List<TableStructure> tableStructures;
    private final Map<TableReference, TableStructure> byTableReference = new ConcurrentHashMap<>();
    private final ListMultimap<String, TableStructure> byTableName = ArrayListMultimap.create();

    public SchemaLoadingService(@Named(MetadataDataSource.NAME) DataSource metadataDataSource,
                                TableColumnService tableColumnService, IndexLoadingService indexLoadingService) {
        this.metadataDataSource = metadataDataSource;
        this.tableColumnService = tableColumnService;
        this.indexLoadingService = indexLoadingService;
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

        return byTableReference.get(table.normalized());
    }


    public List<TableStructure> getAllByTableName(String table) {
        return byTableName.get(table.toLowerCase());
    }

    @PostConstruct
    private void initializeTableStructures() {
        log.info("Initializing table structures");
        long start = System.nanoTime();
        try (Connection connection = getConnection()) {
            try (ResultSet tables = connection.getMetaData().getTables(null, null, null, null)) {
                List<TableStructure> tableStructures = new ArrayList<>();
                while (tables.next()) {
                    // the jdbc driver sends the schema column over in the catalog field
                    String schema = tables.getString("TABLE_CAT");

                    String tableName = tables.getString("TABLE_NAME");
                    log.trace("Reading structure for table {}", tableName);
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

                    TableReference table = TableReference.newTableReference(schema, tableName).normalized();
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
                            tableColumnService.getColumns(table)
                        )
                        .indices(
                            indexLoadingService.getIndexes(table)
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
        log.info("Completed read of table structure in {}", Duration.ofNanos(System.nanoTime() - start));

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
        log.trace("Reading foreign keys for {}", table.toStringNormalized());
        try (ResultSet keys = connection.getMetaData()
            .getImportedKeys(table.getSchemaName(), table.getSchemaName(), table.getTableName())) {

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
        byTableReference.put(reference.normalized(), structure);
        byTableName.put(structure.name().toLowerCase(), structure);
    }
}
