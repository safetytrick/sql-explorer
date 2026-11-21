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
    private final TableStatsService tableStatsService;

    public SchemaRepositoryFactory(TableColumnService tableColumnService,
                                   IndexLoadingService indexLoadingService,
                                   ForeignKeyLoadingService foreignKeyLoadingService,
                                   CreateStatementService createStatementService,
                                   ViewDefinitionService viewDefinitionService) {
        this(tableColumnService, indexLoadingService, foreignKeyLoadingService, createStatementService,
            viewDefinitionService, null, null);
    }

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

    public record SchemaLoad(LoadedSchema schema, SchemaFingerprint fingerprint, boolean fromSnapshot,
                             boolean current, Instant readAt, String readFrom) { }

    public SchemaLoad loadCurrent(DataSource dataSource, SchemaSnapshot.Key key, String server) {
        if (snapshotStore == null || fingerprintService == null || key == null) {
            Map<String, TableStats> stats = readStats(dataSource, server);
            return new SchemaLoad(withCreateStatements(withStats(load(dataSource).getTables(), stats), dataSource),
                null, false, true, Instant.now(), server);
        }
        SchemaFingerprint fingerprint = fingerprintService.fingerprint(dataSource);
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

    private List<TableStructure> refreshedStats(SchemaSnapshot snapshot, Map<String, TableStats> stats) {
        if (stats.isEmpty()) {
            return snapshot.tables();
        }
        List<TableStructure> tables = withStats(snapshot.tables(), stats);
        snapshotStore.write(new SchemaSnapshot(snapshot.format(), snapshot.environment(), snapshot.cluster(),
            snapshot.server(), snapshot.readAt(), snapshot.fingerprint(), tables));
        return tables;
    }

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

    public Optional<SchemaLoad> lastKnown(SchemaSnapshot.Key key) {
        if (snapshotStore == null || key == null) {
            return Optional.empty();
        }
        return snapshotStore.read(key).map(snapshot -> new SchemaLoad(new LoadedSchema(snapshot.tables()),
            snapshot.fingerprint(), true, false, snapshot.readAt(), snapshot.server()));
    }

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

        return withCreateStatements(tableStructures, dataSource);
    }

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
