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
    private final ForeignKeyLoadingService foreignKeyLoadingService;

    // todo remove this global scope and reference this as an environment
    private volatile List<TableStructure> tableStructures;
    private final Map<TableReference, TableStructure> byTableReference = new ConcurrentHashMap<>();
    private final ListMultimap<String, TableStructure> byTableName = ArrayListMultimap.create();

    public SchemaLoadingService(@Named(MetadataDataSource.NAME) DataSource metadataDataSource,
                                TableColumnService tableColumnService,
                                IndexLoadingService indexLoadingService,
                                ForeignKeyLoadingService foreignKeyLoadingService) {
        this.metadataDataSource = metadataDataSource;
        this.tableColumnService = tableColumnService;
        this.indexLoadingService = indexLoadingService;
        this.foreignKeyLoadingService = foreignKeyLoadingService;
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
        TableColumnService.TableColumnsFetcher columnFetcher = tableColumnService.getColumnFetcher();
        ForeignKeyLoadingService.ForeignKeysFetcher foreignKeysFetcher = foreignKeyLoadingService.getForeignKeysFetcher();
        IndexLoadingService.IndicesFetcher indicesFetcher = indexLoadingService.getIndicesFetcher();
        log.info("Initializing table structures");
        long start = System.nanoTime();
        try (Connection connection = getConnection()) {

            long startTables = System.nanoTime();
            List<Map.Entry<TableReference, TableStructure.Builder>> tableBuilders = new ArrayList<>();
            try (ResultSet tables = connection.getMetaData().getTables(null, null, null, null)) {
                while (tables.next()) {
                    var entry = tableStructureEntry(tables, columnFetcher, foreignKeysFetcher, indicesFetcher);
                    tableBuilders.add(entry);
                }
            }
            log.info("Completed reading table metadata in {}", Duration.ofNanos(System.nanoTime() - startTables));


            long startCreateTables = System.nanoTime();
            List<TableStructure> tableStructures = new ArrayList<>();
            for (var entry : tableBuilders) {
                TableReference table = entry.getKey();
                String create = getCreateStatement(connection,
                    table.getSchemaName(),
                    table.getTableName());
                entry.getValue().create(create);

                TableStructure structure = entry.getValue().build();
                tableStructures.add(structure);
                addTableStructure(table, structure);
            }
            log.info("Completed reading create statements in {}", Duration.ofNanos(System.nanoTime() - startCreateTables));

            this.tableStructures = tableStructures;

        } catch (SQLException e) {
            tableStructures = Collections.emptyList();
            throw new IllegalArgumentException(e);
        }
        log.info("Completed read of table structure in {}", Duration.ofNanos(System.nanoTime() - start));

    }

    private Map.Entry<TableReference, TableStructure.Builder> tableStructureEntry(ResultSet tables,
                                                                                  TableColumnService.TableColumnsFetcher columnsFetcher,
                                                                                  ForeignKeyLoadingService.ForeignKeysFetcher foreignKeysFetcher, IndexLoadingService.IndicesFetcher indicesFetcher) throws SQLException {
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

        TableReference table = TableReference.newTableReference(schema, tableName);
        return Map.entry(table, TableStructure.builder()
            .schema(schema)
            .name(tableName)
            .type(tableType)
            .remarks(remarks)
            .foreignKeys(
                foreignKeysFetcher.getForeignKeys(table)
            )
            .columns(
                columnsFetcher.getColumns(table)
            )
            .indices(
                indicesFetcher.getIndices(table)
            ));
    }

    private String getCreateStatement(Connection connection, String schema, String tableName) throws SQLException {
        if ("performance_schema".equals(schema)
            || "sys".equals(schema)
            || "mysql".equals(schema)
            || "information_schema".equals(schema)) {
            return "system table";
        } else if (true) {
            return "skipped this stuff";
        }
        try (Statement statement = connection.createStatement()) {
            try (ResultSet resultSet = statement.executeQuery("show create table `" + schema + "`.`" + tableName+"`")) {
                resultSet.next();

                return resultSet.getString(2);
            }
        }
    }

    private void addTableStructure(TableReference reference, TableStructure structure) {
        byTableReference.put(reference.normalized(), structure);
        byTableName.put(structure.name().toLowerCase(), structure);
    }
}
