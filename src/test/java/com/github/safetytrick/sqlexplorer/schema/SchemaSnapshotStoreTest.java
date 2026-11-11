package com.github.safetytrick.sqlexplorer.schema;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.JDBCType;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaSnapshotStoreTest {

    @TempDir
    Path directory;

    private static final TableReference CUSTOMER = TableReference.newTableReference("app", "customer");
    private static final TableReference ORDERS = TableReference.newTableReference("app", "orders");
    private static final SchemaSnapshot.Key SHARD1 = new SchemaSnapshot.Key("production", "shard1");

    /** Every figure set, nulls included, so each survives the file (sqlx-n5j.2). */
    private static final TableStats CUSTOMER_STATS = new TableStats("shard1", Instant.parse("2026-10-19T17:00:00Z"),
        1_200L, 163_840L, 65_536L, 4_194_304L, 136L, 1_301L, "Dynamic", "InnoDB",
        Instant.parse("2026-01-02T03:04:05Z"), null, "utf8mb4_0900_ai_ci");

    private static TableColumn column(String table, String name, JDBCType type, String remarks) {
        return new TableColumn("app", table, name, type, type.getName(), 10, 0, 10, remarks, null, 0, 1,
            "NO", "NO", "NO", "utf8mb4", "utf8mb4_0900_ai_ci");
    }

    /** Every nested type a loaded schema holds — Avro references included — survives the file. */
    private static SchemaSnapshot snapshot() {
        TableStructure customer = new TableStructure("app", "customer", "TABLE", "people who pay", List.of(),
            List.of(new TableIndex("app", true, null, "uk_email", IndexType.tableIndexOther,
                IndexDirection.ASCENDING, 1, 0, null, List.of("email"))),
            List.of(column("customer", "id", JDBCType.INTEGER, ""), column("customer", "email", JDBCType.VARCHAR, "login address")),
            null, null, List.of(CUSTOMER_STATS));
        TableStructure orders = new TableStructure("app", "orders", "TABLE", "",
            List.of(new ForeignKey.Builder().constraintName("fk_orders_customer").rules("RESTRICT", "CASCADE")
                .addFromAndTo(ColumnReference.newColumnReference(ORDERS, "customer_id"),
                    ColumnReference.newColumnReference(CUSTOMER, "id")).build()),
            List.of(), List.of(column("orders", "customer_id", JDBCType.INTEGER, "")), null, null);
        TableStructure view = new TableStructure("app", "customer_orders", "VIEW", "VIEW", List.of(), List.of(),
            List.of(column("customer_orders", "email", JDBCType.VARCHAR, "login address")),
            List.of(CUSTOMER, ORDERS),
            List.of(new ViewColumn("email", List.of(ColumnReference.newColumnReference(CUSTOMER, "email")), false)));
        SchemaFingerprint fingerprint = SchemaFingerprint.of(Map.of(
            "app.customer", Map.of("columns", 1L), "app.orders", Map.of("columns", 2L)));
        return new SchemaSnapshot(SchemaSnapshotStore.FORMAT, "production", "shard1", "shard1",
            Instant.parse("2026-10-19T17:00:00Z"), fingerprint, List.of(customer, orders, view));
    }

    @Test
    void aSnapshotReadsBackAsItWasWritten() {
        SchemaSnapshotStore store = new SchemaSnapshotStore(directory);
        SchemaSnapshot written = snapshot();

        store.write(written);
        SchemaSnapshot read = store.read(SHARD1).orElseThrow();

        assertThat(read).isEqualTo(written);
        // and it still answers like a schema
        LoadedSchema schema = new LoadedSchema(read.tables());
        assertThat(schema.getTable(ORDERS).foreignKeys().getFirst().to().getFirst().getTable())
            .isEqualTo(CUSTOMER);
        assertThat(schema.getTable(TableReference.newTableReference("app", "customer_orders")).underlyingTables())
            .containsExactly(CUSTOMER, ORDERS);
    }

    @Test
    void statsReadBackAndATableWithoutThemHasNone() {
        SchemaSnapshotStore store = new SchemaSnapshotStore(directory);
        store.write(snapshot());

        LoadedSchema schema = new LoadedSchema(store.read(SHARD1).orElseThrow().tables());
        assertThat(schema.getTable(CUSTOMER).stats()).containsExactly(CUSTOMER_STATS);
        assertThat(schema.getTable(CUSTOMER).rowsEstimate()).isEqualTo(1_200L);
        assertThat(schema.getTable(CUSTOMER).sizeBytes()).isEqualTo(163_840L + 65_536L);
        assertThat(schema.getTable(ORDERS).stats()).isNull();
    }

    /**
     * A snapshot written before stats were kept still loads, with none: the
     * file is read with the current schema as the reader's, and the field it
     * lacks takes its default (sqlx-n5j.2).
     */
    @Test
    void aSnapshotWrittenBeforeStatsStillLoadsWithoutThem() throws Exception {
        SchemaSnapshotStore store = new SchemaSnapshotStore(directory);
        Path file = store.fileFor(SHARD1);
        Files.createDirectories(directory.resolve("production"));
        org.apache.avro.Schema current = com.github.safetytrick.sqlexplorer.avro.SnapshotTable.getClassSchema();
        org.apache.avro.Schema before = org.apache.avro.Schema.createRecord(current.getName(), current.getDoc(),
            current.getNamespace(), false, current.getFields().stream()
                .filter(field -> !field.name().equals("stats"))
                .map(field -> new org.apache.avro.Schema.Field(field, field.schema()))
                .toList());
        SchemaSnapshot written = snapshot();
        try (var writer = org.apache.parquet.avro.AvroParquetWriter
                .<org.apache.avro.generic.GenericRecord>builder(new org.apache.parquet.io.LocalOutputFile(file))
                .withSchema(before)
                .withConf(new org.apache.hadoop.conf.Configuration(false))
                .withExtraMetaData(Map.of(
                    "sqlx.snapshot.format", String.valueOf(SchemaSnapshotStore.FORMAT),
                    "sqlx.snapshot.environment", "production",
                    "sqlx.snapshot.cluster", "shard1",
                    "sqlx.snapshot.server", "shard1",
                    "sqlx.snapshot.readAt", "2026-10-19T17:00:00Z",
                    "sqlx.snapshot.fingerprint", "app.customer\tabc\n"))
                .build()) {
            for (TableStructure table : written.tables()) {
                var row = SchemaSnapshotStore.toRow(table);
                var old = new org.apache.avro.generic.GenericData.Record(before);
                before.getFields().forEach(field -> old.put(field.name(), row.get(field.name())));
                writer.write(old);
            }
        }

        SchemaSnapshot read = store.read(SHARD1).orElseThrow();

        assertThat(read.tables()).hasSize(3).allSatisfy(table -> assertThat(table.stats()).isNull());
        assertThat(read.tables()).containsExactlyElementsOf(written.tables().stream()
            .map(table -> table.withStats(null)).toList());
    }

    @Test
    void noFileIsNoSnapshot() {
        assertThat(new SchemaSnapshotStore(directory).read(SHARD1)).isEmpty();
    }

    /** A snapshot is a cache: anything that cannot be trusted is no snapshot, and gets replaced. */
    @Test
    void aCorruptOrOlderFormatFileIsNoSnapshot() throws Exception {
        SchemaSnapshotStore store = new SchemaSnapshotStore(directory);
        Files.createDirectories(directory.resolve("production"));

        Files.writeString(store.fileFor(SHARD1), "{ not json");
        assertThat(store.read(SHARD1)).as("corrupt").isEmpty();

        SchemaSnapshot old = snapshot();
        store.write(new SchemaSnapshot(SchemaSnapshotStore.FORMAT - 1, old.environment(), old.cluster(),
            old.server(), old.readAt(), old.fingerprint(), old.tables()));
        assertThat(store.read(SHARD1)).as("older format").isEmpty();
    }

    @Test
    void eachClusterHasItsOwnFile() {
        SchemaSnapshotStore store = new SchemaSnapshotStore(directory);
        store.write(snapshot());

        assertThat(store.read(new SchemaSnapshot.Key("production", "shard2"))).isEmpty();
        assertThat(store.read(new SchemaSnapshot.Key("staging", "shard1"))).isEmpty();
    }

    /** Snapshots are Parquet now; a cluster's old JSON one is removed when its Parquet one is written (sqlx-7o3). */
    @Test
    void writingRemovesTheClustersFormerJsonSnapshot() throws Exception {
        SchemaSnapshotStore store = new SchemaSnapshotStore(directory);
        Path cluster = Files.createDirectories(directory.resolve("production"));
        Path json = cluster.resolve("shard1.json");
        Files.writeString(json, "{}");

        store.write(snapshot());

        assertThat(json).doesNotExist();
        assertThat(store.fileFor(SHARD1)).hasFileName("shard1.parquet").exists();
    }
}
