    package com.github.safetytrick.sqlexplorer.schema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.github.safetytrick.sqlexplorer.analysis.resolver.ViewLineageResolver;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Loads the full schema (tables, columns, indexes, foreign keys) from a
 * database server into an immutable {@link LoadedSchema}. Stateless: the
 * DataSource is supplied per call so each
 * {@link com.github.safetytrick.sqlexplorer.environment.DatabaseServer} loads
 * its own schema.
 */
@Service
public class SchemaRepositoryFactory {

    private static final Logger log = LoggerFactory.getLogger(SchemaRepositoryFactory.class);

    private final TableColumnService tableColumnService;
    private final IndexLoadingService indexLoadingService;
    private final ForeignKeyLoadingService foreignKeyLoadingService;
    private final CreateStatementService createStatementService;
    private final ViewDefinitionService viewDefinitionService;

    public SchemaRepositoryFactory(TableColumnService tableColumnService,
                                   IndexLoadingService indexLoadingService,
                                   ForeignKeyLoadingService foreignKeyLoadingService,
                                   CreateStatementService createStatementService,
                                   ViewDefinitionService viewDefinitionService) {
        this.tableColumnService = tableColumnService;
        this.indexLoadingService = indexLoadingService;
        this.foreignKeyLoadingService = foreignKeyLoadingService;
        this.createStatementService = createStatementService;
        this.viewDefinitionService = viewDefinitionService;
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
    public LoadedSchema load(DataSource dataSource) {
        TableColumnService.TableColumnsFetcher columnFetcher = tableColumnService.getColumnFetcher(dataSource);
        ForeignKeyLoadingService.ForeignKeysFetcher foreignKeysFetcher = foreignKeyLoadingService.getForeignKeysFetcher(dataSource);
        IndexLoadingService.IndicesFetcher indicesFetcher = indexLoadingService.getIndicesFetcher(dataSource);
        log.info("Initializing table structures");
        long start = System.nanoTime();
        List<TableStructure> tableStructures = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {

            long startTables = System.nanoTime();
            try (ResultSet tables = connection.getMetaData().getTables(null, null, null, null)) {
                while (tables.next()) {
                    tableStructures.add(tableStructure(tables, columnFetcher, foreignKeysFetcher, indicesFetcher));
                }
            }
            log.info("Completed reading table metadata in {}", Duration.ofNanos(System.nanoTime() - startTables));

        } catch (SQLException e) {
            throw new IllegalArgumentException(e);
        }
        log.info("Completed read of table structure in {}", Duration.ofNanos(System.nanoTime() - start));

        tableStructures = attachViewDependencies(dataSource, tableStructures);

        // The create statement is loaded lazily and cached by LoadedSchema;
        // running `show create table` per table here is slow and only needed on demand.
        return new LoadedSchema(tableStructures,
            table -> createStatementService.loadCreateStatement(dataSource, table));
    }

    /**
     * Resolve each view's base tables and column lineage from
     * information_schema.VIEWS (one bulk query, views only). Column lineage
     * resolves against the structures loaded so far, so view columns map onto
     * base-table columns; views over views resolve one level only.
     */
    private List<TableStructure> attachViewDependencies(DataSource dataSource, List<TableStructure> structures) {
        if (structures.stream().noneMatch(structure -> "VIEW".equalsIgnoreCase(structure.type()))) {
            return structures;
        }
        var definitions = viewDefinitionService.loadViewDefinitions(dataSource);
        LoadedSchema interim = new LoadedSchema(structures);
        return structures.stream()
            .map(structure -> {
                if (!"VIEW".equalsIgnoreCase(structure.type())) {
                    return structure;
                }
                var definition = definitions.get(ViewDefinitionService.key(structure.schema(), structure.name()));
                if (definition == null) {
                    return structure.withViewDefinition(List.of(), List.of());
                }
                return structure.withViewDefinition(definition.underlyingTables(),
                    ViewLineageResolver.resolveColumns(definition.definition(), structure.schema(), interim));
            })
            .toList();
    }

    private TableStructure tableStructure(ResultSet tables,
                                          TableColumnService.TableColumnsFetcher columnsFetcher,
                                          ForeignKeyLoadingService.ForeignKeysFetcher foreignKeysFetcher,
                                          IndexLoadingService.IndicesFetcher indicesFetcher) throws SQLException {
        String schema = tables.getString("TABLE_CAT");

        String tableName = tables.getString("TABLE_NAME");
        log.trace("Reading structure for table {}", tableName);
        if (schema == null) {
            log.warn("null schema for table {}", tableName);
        }
        String tableType = tables.getString("TABLE_TYPE");
        String remarks = tables.getString("REMARKS");

        TableReference table = TableReference.newTableReference(schema, tableName);
        return TableStructure.builder()
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
            )
            .build();
    }
}
