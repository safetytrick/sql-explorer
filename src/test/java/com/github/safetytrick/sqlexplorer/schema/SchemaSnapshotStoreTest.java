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
            null, null);
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
}
