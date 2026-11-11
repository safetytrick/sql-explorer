    package com.github.safetytrick.sqlexplorer.schema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.github.safetytrick.sqlexplorer.analysis.resolver.ViewLineageResolver;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final SchemaFingerprintService fingerprintService;
    private final SchemaSnapshotStore snapshotStore;
    /** Null: no table stats are read (sqlx-n5j.2). */
    private final TableStatsService tableStatsService;

    /** Without snapshots: every load reads the server in full. */
    public SchemaRepositoryFactory(TableColumnService tableColumnService,
                                   IndexLoadingService indexLoadingService,
                                   ForeignKeyLoadingService foreignKeyLoadingService,
                                   CreateStatementService createStatementService,
                                   ViewDefinitionService viewDefinitionService) {
        this(tableColumnService, indexLoadingService, foreignKeyLoadingService, createStatementService,
            viewDefinitionService, null, null);
    }

    /** Without table stats. */
    public SchemaRepositoryFactory(TableColumnService tableColumnService,
                                   IndexLoadingService indexLoadingService,
                                   ForeignKeyLoadingService foreignKeyLoadingService,
                                   CreateStatementService createStatementService,
                                   ViewDefinitionService viewDefinitionService,
                                   SchemaFingerprintService fingerprintService,
                                   SchemaSnapshotStore snapshotStore) {
        this(tableColumnService, indexLoadingService, foreignKeyLoadingService, createStatementService,
            viewDefinitionService, fingerprintService, snapshotStore, null);
    }

    @Autowired
    public SchemaRepositoryFactory(TableColumnService tableColumnService,
                                   IndexLoadingService indexLoadingService,
                                   ForeignKeyLoadingService foreignKeyLoadingService,
                                   CreateStatementService createStatementService,
                                   ViewDefinitionService viewDefinitionService,
                                   SchemaFingerprintService fingerprintService,
                                   SchemaSnapshotStore snapshotStore,
                                   TableStatsService tableStatsService) {
        this.tableStatsService = tableStatsService;
        this.tableColumnService = tableColumnService;
        this.indexLoadingService = indexLoadingService;
        this.foreignKeyLoadingService = foreignKeyLoadingService;
        this.createStatementService = createStatementService;
        this.viewDefinitionService = viewDefinitionService;
        this.fingerprintService = fingerprintService;
        this.snapshotStore = snapshotStore;
    }

    /**
     * A schema and how it was obtained.
     *
     * @param fingerprint  the server's fingerprint when it was loaded; null when
     *                     snapshots are off, and then there is nothing to revalidate against
     * @param fromSnapshot read from the cluster's snapshot rather than the server
     * @param current      whether it is known to match the server now — false
     *                     only for {@link #lastKnown}, which no server confirmed
     * @param readAt       when the schema itself was read from a server
     * @param readFrom     which server it was read from
     */
    public record SchemaLoad(LoadedSchema schema, SchemaFingerprint fingerprint, boolean fromSnapshot,
                             boolean current, Instant readAt, String readFrom) { }

    /**
     * The cluster's schema as the server has it now: from the snapshot when the
     * server's fingerprint still matches it, otherwise read in full and
     * snapshotted (sqlx-sqy.4).
     *
     * <p>The fingerprint is taken before a full read, so a schema changing
     * mid-read leaves the snapshot labelled with the older fingerprint — the
     * next check sees the difference and reads again, rather than trusting it.
     */
    public SchemaLoad loadCurrent(DataSource dataSource, SchemaSnapshot.Key key, String server) {
        if (snapshotStore == null || fingerprintService == null || key == null) {
            Map<String, TableStats> stats = readStats(dataSource, server);
            return new SchemaLoad(withCreateStatements(withStats(load(dataSource).getTables(), stats), dataSource),
                null, false, true, Instant.now(), server);
        }
        SchemaFingerprint fingerprint = fingerprintService.fingerprint(dataSource);
        // read beside the fingerprint, on the connection a load makes anyway: stats move
        // with the data, so a snapshot that is still current structurally is not current here
        Map<String, TableStats> stats = readStats(dataSource, server);
        Optional<SchemaSnapshot> snapshot = snapshotStore.read(key);
        if (snapshot.isPresent() && snapshot.get().fingerprint().equals(fingerprint)) {
            log.info("Schema of {}/{} unchanged since {} (read from {}); using its snapshot",
                key.environment(), key.cluster(), snapshot.get().readAt(), snapshot.get().server());
            List<TableStructure> tables = refreshedStats(snapshot.get(), stats);
            return new SchemaLoad(withCreateStatements(tables, dataSource), fingerprint, true, true,
                snapshot.get().readAt(), snapshot.get().server());
        }
        log.info("Schema of {}/{} {}; reading it from {}", key.environment(), key.cluster(),
            snapshot.isPresent() ? "changed since its snapshot" : "has no snapshot", server);
        Instant readAt = Instant.now();
        List<TableStructure> tables = withStats(load(dataSource).getTables(), stats);
        snapshotStore.write(new SchemaSnapshot(SchemaSnapshotStore.FORMAT, key.environment(), key.cluster(), server,
            readAt, fingerprint, tables));
        return new SchemaLoad(withCreateStatements(tables, dataSource), fingerprint, false, true, readAt, server);
    }

    /**
     * A schema still current, with its table stats read again — what a pull
     * does for a server whose schema it keeps in memory, so that sizes and row
     * estimates move with the data while the structure is not re-read
     * (sqlx-n5j.2). The cluster's snapshot is rewritten with them, so a later
     * cached start shows them too. A failed read keeps the stats it had.
     */
    public SchemaLoad withFreshStats(DataSource dataSource, SchemaLoad loaded, SchemaSnapshot.Key key,
                                     String server) {
        Map<String, TableStats> stats = readStats(dataSource, server);
        if (stats.isEmpty()) {
            return loaded;
        }
        List<TableStructure> tables = withStats(loaded.schema().getTables(), stats);
        if (snapshotStore != null && key != null && loaded.fingerprint() != null) {
            snapshotStore.write(new SchemaSnapshot(SchemaSnapshotStore.FORMAT, key.environment(), key.cluster(),
                loaded.readFrom(), loaded.readAt(), loaded.fingerprint(), tables));
        }
        return new SchemaLoad(withCreateStatements(tables, dataSource), loaded.fingerprint(), loaded.fromSnapshot(),
            loaded.current(), loaded.readAt(), loaded.readFrom());
    }

    /** The snapshot's tables with these stats, rewriting the snapshot so it keeps them; as it was when there are none. */
    private List<TableStructure> refreshedStats(SchemaSnapshot snapshot, Map<String, TableStats> stats) {
        if (stats.isEmpty()) {
            return snapshot.tables();
        }
        List<TableStructure> tables = withStats(snapshot.tables(), stats);
        snapshotStore.write(new SchemaSnapshot(snapshot.format(), snapshot.environment(), snapshot.cluster(),
            snapshot.server(), snapshot.readAt(), snapshot.fingerprint(), tables));
        return tables;
    }

    /**
     * Stats are an extra, not part of the schema: one that cannot be read
     * leaves the tables without them, and the load goes on.
     */
    private Map<String, TableStats> readStats(DataSource dataSource, String server) {
        if (tableStatsService == null) {
            return Map.of();
        }
        try {
            return tableStatsService.read(dataSource, server);
        } catch (RuntimeException e) {
            log.warn("Table statistics of server {} could not be read: {}", server, e.toString());
            return Map.of();
        }
    }

    /** Each table with the one server's stats for it; a table it did not report (a view) has none. */
    static List<TableStructure> withStats(List<TableStructure> tables, Map<String, TableStats> stats) {
        if (stats.isEmpty()) {
            return tables;
        }
        return tables.stream()
            .map(table -> {
                TableStats reported = stats.get(SchemaFingerprint.key(table.schema(), table.name()));
                return table.withStats(reported == null ? null : List.of(reported));
            })
            .toList();
    }

    /**
     * The cluster's last snapshot, for when no server can be reached to confirm
     * it. Not current — it may describe a schema since changed — so it serves
     * what was already analysed and nothing new is analysed against it.
     * {@code SHOW CREATE} is unavailable through it: there is no connection.
     */
    public Optional<SchemaLoad> lastKnown(SchemaSnapshot.Key key) {
        if (snapshotStore == null || key == null) {
            return Optional.empty();
        }
        return snapshotStore.read(key).map(snapshot -> new SchemaLoad(new LoadedSchema(snapshot.tables()),
            snapshot.fingerprint(), true, false, snapshot.readAt(), snapshot.server()));
    }

    /** Whether the server's schema still matches the one loaded; any failure to tell is a no. */
    public boolean stillCurrent(DataSource dataSource, SchemaFingerprint loaded) {
        if (fingerprintService == null || loaded == null) {
            return false;
        }
        try {
            return fingerprintService.fingerprint(dataSource).equals(loaded);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private LoadedSchema withCreateStatements(List<TableStructure> tables, DataSource dataSource) {
        return new LoadedSchema(tables, table -> createStatementService.loadCreateStatement(dataSource, table));
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
        return withCreateStatements(tableStructures, dataSource);
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
