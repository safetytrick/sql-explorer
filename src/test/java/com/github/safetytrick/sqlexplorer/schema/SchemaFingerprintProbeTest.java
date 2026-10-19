package com.github.safetytrick.sqlexplorer.schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fingerprint against a real MySQL (sqlx-sqy.3): it moves when the loader
 * would read something different, and only then.
 *
 * <p>Not a CI test. It runs against the fixture in
 * {@code src/test/resources/grant-profiles-probe.sql} (which says how to start
 * it), as root for the DDL, and only when {@code GRANTS_PROBE_URL} is set. It
 * alters the fixture, so load a fresh one for each run.
 */
@EnabledIfEnvironmentVariable(named = "GRANTS_PROBE_URL", matches = ".+")
class SchemaFingerprintProbeTest {

    private final SchemaFingerprintService service = new SchemaFingerprintService();
    private final String url = System.getenv("GRANTS_PROBE_URL");
    private final DriverManagerDataSource root = new DriverManagerDataSource(url, "root", "root");

    private SchemaFingerprint fingerprint(String account) {
        return service.fingerprint(new DriverManagerDataSource(url, account, "pw"));
    }

    private void execute(String... statements) throws SQLException {
        try (Connection connection = root.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    @Test
    void movesForSchemaChangesAndOnlyThose() throws SQLException {
        SchemaFingerprint before = service.fingerprint(root);
        assertThat(before.tables()).containsKeys("app.customer", "app.orders", "app.customer_orders", "other.secret");

        // data and statistics: the loader would read the same schema
        execute("INSERT INTO app.customer VALUES (2, 'b@example.com'), (3, 'c@example.com')",
            "INSERT INTO app.orders VALUES (2, 2, 1.00), (3, 3, 2.00)",
            "ANALYZE TABLE app.customer, app.orders");
        assertThat(service.fingerprint(root).digest()).as("data and index statistics").isEqualTo(before.digest());

        execute("ALTER TABLE app.customer MODIFY email VARCHAR(100) NOT NULL COMMENT 'sign-in address'");
        SchemaFingerprint commented = service.fingerprint(root);
        // the view too: a view's columns carry their base column's comment in
        // information_schema.COLUMNS, so the loader reads it changed there as well
        assertThat(before.differingTables(commented)).as("a column comment")
            .containsExactly("app.customer", "app.customer_orders");

        execute("CREATE INDEX ix_total ON app.orders (total)");
        SchemaFingerprint indexed = service.fingerprint(root);
        assertThat(commented.differingTables(indexed)).as("a new index").containsExactly("app.orders");

        execute("ALTER TABLE app.orders DROP FOREIGN KEY fk_orders_customer");
        SchemaFingerprint unlinked = service.fingerprint(root);
        assertThat(indexed.differingTables(unlinked)).as("a dropped foreign key").contains("app.orders");

        execute("CREATE TABLE app.invoices (id INT PRIMARY KEY)");
        assertThat(unlinked.differingTables(service.fingerprint(root))).as("a new table")
            .containsExactly("app.invoices");
    }

    /**
     * Under each grant profile that can see the whole structure, the account
     * sees one fingerprint — the snapshot a metadata-only account takes
     * matches the one an account that can read rows takes. SHOW VIEW alone
     * cannot see columns (docs/mysql-performance-schema.md), and its
     * fingerprint says so by differing.
     */
    @Test
    void everyFullMetadataProfileSeesTheSameFingerprint() {
        SchemaFingerprint selecting = fingerprint("p_select_showview").onlySchema("app");

        assertThat(fingerprint("p_references_showview").onlySchema("app").digest()).isEqualTo(selecting.digest());
        assertThat(fingerprint("p_select").onlySchema("app").digest()).isEqualTo(selecting.digest());
        assertThat(fingerprint("p_showview").onlySchema("app").digest()).isNotEqualTo(selecting.digest());
    }
}
