package com.github.safetytrick.sqlexplorer.schema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.JDBCType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.github.safetytrick.sqlexplorer.avro.SnapshotColumn;
import com.github.safetytrick.sqlexplorer.avro.SnapshotForeignKey;
import com.github.safetytrick.sqlexplorer.avro.SnapshotIndex;
import com.github.safetytrick.sqlexplorer.avro.SnapshotTable;
import com.github.safetytrick.sqlexplorer.avro.SnapshotTableStats;
import com.github.safetytrick.sqlexplorer.avro.SnapshotViewColumn;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetConfig;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SchemaSnapshotStore {

    public static final int FORMAT = 2;

    private static final Logger log = LoggerFactory.getLogger(SchemaSnapshotStore.class);
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._-]");

    private static final String META = "sqlx.snapshot.";
    private static final String FORMAT_KEY = META + "format";
    private static final String ENVIRONMENT_KEY = META + "environment";
    private static final String CLUSTER_KEY = META + "cluster";
    private static final String SERVER_KEY = META + "server";
    private static final String READ_AT_KEY = META + "readAt";
    private static final String FINGERPRINT_KEY = META + "fingerprint";

    private final Path directory;

    @Autowired
    public SchemaSnapshotStore(ParquetConfig config) {
        this(besideCaptures(Path.of(config.getFilename()).toAbsolutePath()));
    }

    private static Path besideCaptures(Path captureBase) {
        Path archive = captureBase.getParent();
        return (archive == null ? captureBase : archive).resolve("schema-snapshots");
    }

    public SchemaSnapshotStore(Path directory) {
        this.directory = directory;
    }

    public Optional<SchemaSnapshot> read(SchemaSnapshot.Key key) {
        Path file = fileFor(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            Map<String, String> footer;
            try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(file))) {
                footer = reader.getFooter().getFileMetaData().getKeyValueMetaData();
            }
            int format = Integer.parseInt(footer.getOrDefault(FORMAT_KEY, "0"));
            if (format != FORMAT) {
                log.info("Schema snapshot {} is format {}, not {}; it will be replaced", file, format, FORMAT);
                return Optional.empty();
            }
            List<TableStructure> tables = new ArrayList<>();
            PlainParquetConfiguration conf = new PlainParquetConfiguration();
            conf.set("parquet.avro.read.schema", SnapshotTable.getClassSchema().toString());
            try (ParquetReader<SnapshotTable> rows = AvroParquetReader.<SnapshotTable>builder(
                    new LocalInputFile(file), conf).build()) {
                for (SnapshotTable row = rows.read(); row != null; row = rows.read()) {
                    tables.add(fromRow(row));
                }
            }
            return Optional.of(new SchemaSnapshot(format, footer.get(ENVIRONMENT_KEY), footer.get(CLUSTER_KEY),
                footer.get(SERVER_KEY), Instant.parse(footer.get(READ_AT_KEY)),
                fingerprintFrom(footer.getOrDefault(FINGERPRINT_KEY, "")), List.copyOf(tables)));
        } catch (IOException | RuntimeException e) {
            log.warn("Schema snapshot {} could not be read and will be replaced: {}", file, e.toString());
            return Optional.empty();
        }
    }

    public void write(SchemaSnapshot snapshot) {
        SchemaSnapshot.Key key = new SchemaSnapshot.Key(snapshot.environment(), snapshot.cluster());
        Path file = fileFor(key);
        Path partial = file.resolveSibling(file.getFileName() + ".partial");
        try {
            Path cluster = file.getParent();
            if (cluster != null) {
                Files.createDirectories(cluster);
            }
            try (ParquetWriter<SnapshotTable> writer = AvroParquetWriter.<SnapshotTable>builder(new LocalOutputFile(partial))
                .withSchema(SnapshotTable.getClassSchema())
                .withDictionaryEncoding(true)
                .withConf(new Configuration(false))
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .withExtraMetaData(footerOf(snapshot))
                .build()) {
                for (TableStructure table : snapshot.tables()) {
                    writer.write(toRow(table));
                }
            }
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.deleteIfExists(legacyJsonFor(key));
        } catch (IOException | RuntimeException e) {
            log.warn("Schema snapshot {} could not be written: {}", file, e.toString());
            try {
                Files.deleteIfExists(partial);
            } catch (IOException ignored) {
            }
        }
    }

    Path fileFor(SchemaSnapshot.Key key) {
        return directory.resolve(safe(key.environment())).resolve(safe(key.cluster()) + ".parquet");
    }

    private Path legacyJsonFor(SchemaSnapshot.Key key) {
        return directory.resolve(safe(key.environment())).resolve(safe(key.cluster()) + ".json");
    }

    private static String safe(String name) {
        return UNSAFE.matcher(name).replaceAll("_");
    }

    private static Map<String, String> footerOf(SchemaSnapshot snapshot) {
        Map<String, String> footer = new LinkedHashMap<>();
        footer.put(FORMAT_KEY, String.valueOf(snapshot.format()));
        footer.put(ENVIRONMENT_KEY, snapshot.environment());
        footer.put(CLUSTER_KEY, snapshot.cluster());
        footer.put(SERVER_KEY, snapshot.server());
        footer.put(READ_AT_KEY, snapshot.readAt().toString());
        StringBuilder fingerprint = new StringBuilder();
        new TreeMap<>(snapshot.fingerprint().tables())
            .forEach((table, hash) -> fingerprint.append(table).append('\t').append(hash).append('\n'));
        footer.put(FINGERPRINT_KEY, fingerprint.toString());
        return footer;
    }

    private static SchemaFingerprint fingerprintFrom(String lines) {
        Map<String, String> tables = new TreeMap<>();
        for (String line : lines.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) {
                tables.put(line.substring(0, tab), line.substring(tab + 1));
            }
        }
        return new SchemaFingerprint(tables);
    }

    static SnapshotTable toRow(TableStructure table) {
        return SnapshotTable.newBuilder()
            .setSchemaName(table.schema())
            .setName(table.name())
            .setType(table.type())
            .setRemarks(table.remarks())
            .setForeignKeys(listOf(table.foreignKeys()).stream().map(fk -> SnapshotForeignKey.newBuilder()
                .setFrom(fk.from()).setTo(fk.to()).setConstraintName(fk.constraintName())
                .setOnUpdate(fk.onUpdate()).setOnDelete(fk.onDelete()).build()).toList())
            .setIndices(listOf(table.indices()).stream().map(index -> SnapshotIndex.newBuilder()
                .setSchemaName(index.schema()).setUnique(index.unique()).setIdentifier(index.identifier())
                .setName(index.name()).setIndexType(nameOf(index.indexType())).setDirection(nameOf(index.direction()))
                .setCardinality(index.cardinality()).setPages(index.pages())
                .setFilterCondition(index.filterCondition()).setColumns(listOf(index.columns())).build()).toList())
            .setColumns(listOf(table.columns()).stream().map(column -> SnapshotColumn.newBuilder()
                .setSchemaName(column.schema()).setTable(column.table()).setColumnName(column.columnName())
                .setJdbcType(nameOf(column.type())).setTypeName(column.typeName())
                .setColumnSize(column.columnSize()).setDecimalDigits(column.decimalDigits()).setRadix(column.radix())
                .setRemarks(column.remarks()).setColumnDefault(column.columnDefault())
                .setOctetLength(column.octetLength()).setOrdinal(column.ordinal())
                .setIsNullable(column.isNullable()).setIsAutoIncrement(column.isAutoIncrement())
                .setIsGenerated(column.isGenerated()).setCharacterSet(column.characterSet())
                .setCollation(column.collation()).build()).toList())
            .setUnderlyingTables(table.underlyingTables())
            .setViewColumns(table.viewColumns() == null ? null : table.viewColumns().stream()
                .map(view -> SnapshotViewColumn.newBuilder().setName(view.name())
                    .setSources(listOf(view.sources())).setComputed(view.computed()).build()).toList())
            .setStats(table.stats() == null || table.stats().isEmpty() ? null : toRow(table.stats().getFirst()))
            .build();
    }

    private static SnapshotTableStats toRow(TableStats stats) {
        return SnapshotTableStats.newBuilder()
            .setServer(stats.server())
            .setReadAt(stats.readAt().toEpochMilli())
            .setRowsEstimate(stats.rowsEstimate())
            .setDataLength(stats.dataLength())
            .setIndexLength(stats.indexLength())
            .setDataFree(stats.dataFree())
            .setAvgRowLength(stats.avgRowLength())
            .setAutoIncrement(stats.autoIncrement())
            .setRowFormat(stats.rowFormat())
            .setEngine(stats.engine())
            .setCreateTime(millis(stats.createTime()))
            .setUpdateTime(millis(stats.updateTime()))
            .setTableCollation(stats.tableCollation())
            .build();
    }

    private static TableStats fromRow(SnapshotTableStats row) {
        return new TableStats(row.getServer(), Instant.ofEpochMilli(row.getReadAt()), row.getRowsEstimate(),
            row.getDataLength(), row.getIndexLength(), row.getDataFree(), row.getAvgRowLength(),
            row.getAutoIncrement(), row.getRowFormat(), row.getEngine(), instant(row.getCreateTime()),
            instant(row.getUpdateTime()), row.getTableCollation());
    }

    private static Long millis(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    private static Instant instant(Long millis) {
        return millis == null ? null : Instant.ofEpochMilli(millis);
    }

    static TableStructure fromRow(SnapshotTable row) {
        return new TableStructure(row.getSchemaName(), row.getName(), row.getType(), row.getRemarks(),
            row.getForeignKeys().stream().map(fk -> new ForeignKey(List.copyOf(fk.getFrom()), List.copyOf(fk.getTo()),
                fk.getConstraintName(), fk.getOnUpdate(), fk.getOnDelete())).toList(),
            row.getIndices().stream().map(index -> new TableIndex(index.getSchemaName(), index.getUnique(),
                index.getIdentifier(), index.getName(),
                index.getIndexType() == null ? null : IndexType.valueOf(index.getIndexType()),
                index.getDirection() == null ? null : IndexDirection.valueOf(index.getDirection()),
                index.getCardinality(), index.getPages(), index.getFilterCondition(),
                List.copyOf(index.getColumns()))).toList(),
            row.getColumns().stream().map(column -> new TableColumn(column.getSchemaName(), column.getTable(),
                column.getColumnName(), column.getJdbcType() == null ? null : JDBCType.valueOf(column.getJdbcType()),
                column.getTypeName(), column.getColumnSize(), column.getDecimalDigits(), column.getRadix(),
                column.getRemarks(), column.getColumnDefault(), column.getOctetLength(), column.getOrdinal(),
                column.getIsNullable(), column.getIsAutoIncrement(), column.getIsGenerated(),
                column.getCharacterSet(), column.getCollation())).toList(),
            row.getUnderlyingTables() == null ? null : List.copyOf(row.getUnderlyingTables()),
            row.getViewColumns() == null ? null : row.getViewColumns().stream()
                .map(view -> new ViewColumn(view.getName(), List.copyOf(view.getSources()), view.getComputed()))
                .toList(),
            row.getStats() == null ? null : List.of(fromRow(row.getStats())));
    }

    private static <T> List<T> listOf(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
