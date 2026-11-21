package com.github.safetytrick.sqlexplorer.schema;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaFingerprintTest {

    private static SchemaFingerprint fingerprint(Map<String, Map<String, Long>> parts) {
        return SchemaFingerprint.of(parts);
    }

    @Test
    void theSameMetadataInAnyOrderGivesTheSameDigest() {
        SchemaFingerprint a = fingerprint(Map.of(
            "app.users", Map.of("columns", 1L, "indexes", 2L),
            "app.orders", Map.of("columns", 3L)));
        SchemaFingerprint b = fingerprint(Map.of(
            "app.orders", Map.of("columns", 3L),
            "app.users", Map.of("indexes", 2L, "columns", 1L)));

        assertThat(a.digest()).isEqualTo(b.digest());
        assertThat(a.differingTables(b)).isEmpty();
    }

    @Test
    void anAlteredAddedOrDroppedTableIsNamed() {
        SchemaFingerprint before = fingerprint(Map.of(
            "app.users", Map.of("columns", 1L),
            "app.orders", Map.of("columns", 3L),
            "app.legacy", Map.of("columns", 9L)));
        SchemaFingerprint after = fingerprint(Map.of(
            "app.users", Map.of("columns", 1L),
            "app.orders", Map.of("columns", 4L),
            "app.invoices", Map.of("columns", 5L)));

        assertThat(before.digest()).isNotEqualTo(after.digest());
        assertThat(before.differingTables(after)).containsExactly("app.invoices", "app.legacy", "app.orders");
    }

    @Test
    void partsAreNotInterchangeable() {
        assertThat(fingerprint(Map.of("app.t", Map.of("columns", 1L, "indexes", 2L))).digest())
            .isNotEqualTo(fingerprint(Map.of("app.t", Map.of("columns", 2L, "indexes", 1L))).digest());
    }

    @Test
    void oneSchemaCanBeComparedOnItsOwn() {
        SchemaFingerprint shard1 = fingerprint(Map.of(
            "app_production.users", Map.of("columns", 1L), "app_admin.accounts", Map.of("columns", 7L)));
        SchemaFingerprint shard2 = fingerprint(Map.of(
            "app_production.users", Map.of("columns", 1L), "app_admin.accounts", Map.of("columns", 8L)));

        assertThat(shard1.onlySchema("app_production").differingTables(shard2.onlySchema("app_production")))
            .isEmpty();
        assertThat(shard1.differingTables(shard2)).containsExactly("app_admin.accounts");
    }
}
