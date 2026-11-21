package com.github.safetytrick.sqlexplorer.schema;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IndexLoadingServiceTest {

    private final IndexLoadingService service = new IndexLoadingService();

    @Test
    void doesNotLeakTheLastIndexOfOneTableIntoTheNext() throws SQLException {
        ResultSet indexInfo = resultSet(
            row("app", "orders", "PRIMARY", (short) 1, "id", false),
            row("app", "orders", "idx_orders_member", (short) 1, "member_id", true),
            row("app", "sessions", "PRIMARY", (short) 1, "id", false),
            row("app", "sessions", "idx_sessions_user", (short) 1, "user_id", true));

        Map<TableReference, List<TableIndex>> byTable = service.readIndices(indexInfo);

        assertThat(names(byTable, "app", "orders"))
            .containsExactly("PRIMARY", "idx_orders_member");
        assertThat(names(byTable, "app", "sessions"))
            .containsExactly("PRIMARY", "idx_sessions_user");
    }

    @Test
    void doesNotDuplicateAnIndexWhoseNameSpansTheTableBoundary() throws SQLException {
        ResultSet indexInfo = resultSet(
            row("app_other", "accounts", "idx_accounts_name", (short) 1, "name", true),
            row("app_other", "accounts", "PRIMARY", (short) 1, "id", false),
            // same index name either side of the boundary — the case that duplicated
            row("app_other", "groups", "PRIMARY", (short) 1, "id", false),
            row("app_other", "groups", "index_status", (short) 1, "status", true));

        Map<TableReference, List<TableIndex>> byTable = service.readIndices(indexInfo);

        assertThat(names(byTable, "app_other", "accounts"))
            .containsExactly("idx_accounts_name", "PRIMARY");
        assertThat(names(byTable, "app_other", "groups"))
            .containsExactly("PRIMARY", "index_status")
            .doesNotHaveDuplicates();
        assertThat(columns(byTable, "app_other", "groups", "PRIMARY")).containsExactly("id");
    }

    @Test
    void groupsTheRowsOfAMultiColumnIndexIntoOne() throws SQLException {
        ResultSet indexInfo = resultSet(
            row("app", "orders", "idx_composite", (short) 1, "a", true),
            row("app", "orders", "idx_composite", (short) 2, "b", true),
            row("app", "orders", "idx_composite", (short) 3, "c", true));

        Map<TableReference, List<TableIndex>> byTable = service.readIndices(indexInfo);

        assertThat(names(byTable, "app", "orders")).containsExactly("idx_composite");
        assertThat(columns(byTable, "app", "orders", "idx_composite")).containsExactly("a", "b", "c");
    }

    @Test
    void keepsTheFinalIndexOfTheFinalTable() throws SQLException {
        ResultSet indexInfo = resultSet(
            row("app", "orders", "PRIMARY", (short) 1, "id", false),
            row("app", "sessions", "PRIMARY", (short) 1, "id", false));

        Map<TableReference, List<TableIndex>> byTable = service.readIndices(indexInfo);

        assertThat(names(byTable, "app", "sessions")).containsExactly("PRIMARY");
    }

    /**
     * `INDEX_NAME` is null for a `tableIndexStatistic` row, which describes the
     * table rather than an index and carries no columns.
     */
    @Test
    void skipsStatisticRowsThatCarryNoIndexName() throws SQLException {
        ResultSet indexInfo = resultSet(
            row("app", "orders", null, (short) 0, null, true),
            row("app", "orders", "PRIMARY", (short) 1, "id", false));

        Map<TableReference, List<TableIndex>> byTable = service.readIndices(indexInfo);

        assertThat(names(byTable, "app", "orders")).containsExactly("PRIMARY");
    }

    private static List<String> names(Map<TableReference, List<TableIndex>> byTable,
                                      String schema, String table) {
        return byTable.getOrDefault(ref(schema, table), List.of())
            .stream().map(TableIndex::name).toList();
    }

    private static List<String> columns(Map<TableReference, List<TableIndex>> byTable,
                                        String schema, String table, String index) {
        return byTable.getOrDefault(ref(schema, table), List.of()).stream()
            .filter(i -> index.equals(i.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no index " + index + " on " + schema + "." + table))
            .columns();
    }

    private static TableReference ref(String schema, String table) {
        return TableReference.newTableReference(schema, table).normalized();
    }

    private record Row(String schema, String table, String indexName,
                       short ordinalPosition, String columnName, boolean nonUnique) { }

    private static Row row(String schema, String table, String indexName,
                           short ordinalPosition, String columnName, boolean nonUnique) {
        return new Row(schema, table, indexName, ordinalPosition, columnName, nonUnique);
    }

    private static ResultSet resultSet(Row... rows) throws SQLException {
        AtomicInteger cursor = new AtomicInteger(-1);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.next()).thenAnswer(inv -> cursor.incrementAndGet() < rows.length);
        when(resultSet.getString(anyString())).thenAnswer(inv -> {
            Row row = rows[cursor.get()];
            String column = inv.getArgument(0);
            return switch (column) {
                case "TABLE_CAT" -> row.schema();
                case "TABLE_NAME" -> row.table();
                case "INDEX_NAME" -> row.indexName();
                case "COLUMN_NAME" -> row.columnName();
                case "INDEX_QUALIFIER", "ASC_OR_DESC", "FILTER_CONDITION" -> null;
                default -> throw new IllegalArgumentException(column);
            };
        });
        when(resultSet.getBoolean(anyString())).thenAnswer(inv -> rows[cursor.get()].nonUnique());
        when(resultSet.getShort(anyString())).thenAnswer(inv -> {
            String column = inv.getArgument(0);
            return "ORDINAL_POSITION".equals(column) ? rows[cursor.get()].ordinalPosition() : (short) 3;
        });
        when(resultSet.getLong(anyString())).thenAnswer(inv -> 0L);
        return resultSet;
    }
}
