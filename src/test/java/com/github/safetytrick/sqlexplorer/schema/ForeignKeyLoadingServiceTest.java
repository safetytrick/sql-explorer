package com.github.safetytrick.sqlexplorer.schema;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ForeignKeyLoadingServiceTest {

    private final ForeignKeyLoadingService service = new ForeignKeyLoadingService();

    @Test
    void groupsConstraintsByOwningTableAndConstraintName() throws SQLException {
        // ordered by owner, constraint, key_seq — as the query guarantees
        ResultSet keys = resultSet(
            row("app", "orders", "fk_orders_customer", "customer_id", "app", "customers", "id"),
            // composite FK: two rows, one constraint
            row("app", "orders", "fk_orders_line", "line_a", "app", "lines", "a"),
            row("app", "orders", "fk_orders_line", "line_b", "app", "lines", "b"),
            // next owning table: previous builder must flush into orders, not leak here
            row("app", "sessions", "fk_sessions_user", "user_id", "app", "users", "id"));

        Map<TableReference, List<ForeignKey>> byTable = service.readForeignKeys(keys);

        List<ForeignKey> orders = byTable.get(ref("app", "orders"));
        assertThat(orders).hasSize(2);
        assertThat(orders.getFirst().constraintName()).isEqualTo("fk_orders_customer");
        assertThat(orders.getFirst().onUpdate()).isEqualTo("CASCADE");
        assertThat(orders.getFirst().onDelete()).isEqualTo("RESTRICT");
        assertThat(columns(orders.get(0).from())).containsExactly("customer_id");
        assertThat(columns(orders.get(1).from())).containsExactly("line_a", "line_b");
        assertThat(columns(orders.get(1).to())).containsExactly("a", "b");

        List<ForeignKey> sessions = byTable.get(ref("app", "sessions"));
        assertThat(sessions).hasSize(1);
        assertThat(columns(sessions.getFirst().from())).containsExactly("user_id");
    }

    @Test
    void toColumnsPointAtTheReferencedTable() throws SQLException {
        ResultSet keys = resultSet(
            row("app_main", "demo_uuids", "fk_demo", "demo_id", "app_main", "demos", "id"));

        Map<TableReference, List<ForeignKey>> byTable = service.readForeignKeys(keys);

        ColumnReference to = byTable.get(ref("app_main", "demo_uuids")).getFirst().to().getFirst();
        assertThat(to.getTable().getNormalizedTableName()).isEqualTo("demos");
        assertThat(to.getColumn()).isEqualTo("id");
    }

    private static TableReference ref(String schema, String table) {
        return TableReference.newTableReference(schema, table).normalized();
    }

    private static List<String> columns(List<ColumnReference> refs) {
        return refs.stream().map(ColumnReference::getColumn).toList();
    }

    private record Row(String fkSchema, String fkTable, String fkName, String fkColumn,
                       String pkSchema, String pkTable, String pkColumn) { }

    private static Row row(String fkSchema, String fkTable, String fkName, String fkColumn,
                           String pkSchema, String pkTable, String pkColumn) {
        return new Row(fkSchema, fkTable, fkName, fkColumn, pkSchema, pkTable, pkColumn);
    }

    private static ResultSet resultSet(Row... rows) throws SQLException {
        AtomicInteger cursor = new AtomicInteger(-1);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.next()).thenAnswer(inv -> cursor.incrementAndGet() < rows.length);
        when(resultSet.getString(anyString())).thenAnswer(inv -> {
            Row row = rows[cursor.get()];
            String column = inv.getArgument(0);
            return switch (column) {
                case "FKTABLE_CAT" -> row.fkSchema();
                case "FKTABLE_NAME" -> row.fkTable();
                case "FK_NAME" -> row.fkName();
                case "FKCOLUMN_NAME" -> row.fkColumn();
                case "PKTABLE_CAT" -> row.pkSchema();
                case "PKTABLE_NAME" -> row.pkTable();
                case "PKCOLUMN_NAME" -> row.pkColumn();
                case "UPDATE_RULE_NAME" -> "CASCADE";
                case "DELETE_RULE_NAME" -> "RESTRICT";
                default -> throw new IllegalArgumentException(column);
            };
        });
        return resultSet;
    }
}
