package com.github.safetytrick.sqlexplorer.profiles;

import java.math.BigDecimal;
import java.sql.JDBCType;
import java.util.List;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryService;
import com.github.safetytrick.sqlexplorer.profiles.TableInsightsService.ColumnUsage;
import com.github.safetytrick.sqlexplorer.profiles.TableInsightsService.TableStats;
import com.github.safetytrick.sqlexplorer.schema.ForeignKey;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaService;
import com.github.safetytrick.sqlexplorer.schema.TableColumn;
import com.github.safetytrick.sqlexplorer.schema.TableIndex;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TableInsightsServiceTest {

    private static final TableReference SESSIONS = new TableReference("app", "sessions", "sessions", "app");
    private static final TableReference USERS = new TableReference("app", "users", "users", "app");

    private SqlRepositoryService sqlRepositoryService;
    private SqlSchemaService sqlSchemaService;
    private TableInsightsService service;

    @BeforeEach
    void setUp() {
        sqlRepositoryService = mock(SqlRepositoryService.class);
        sqlSchemaService = mock(SqlSchemaService.class);
        service = new TableInsightsService(sqlRepositoryService, sqlSchemaService);
    }

    @Test
    void mergesColumnMetadataWithQueryRoles() {
        when(sqlSchemaService.getTable(SESSIONS)).thenReturn(structure());
        when(sqlRepositoryService.getTableQueries(SESSIONS)).thenAnswer(inv -> Stream.of(
            profile("q1", null,
                // raw name keeps the backticks; matching must use the normalized name
                List.of(new ColumnReference(SESSIONS, "`status`", "status")),
                List.of(join(SESSIONS, "user_id", USERS, "id"))),
            profile("q2", null,
                List.of(criteria(SESSIONS, "user_id"), criteria(USERS, "email")),
                List.of())));

        List<ColumnUsage> columns = service.getColumns(SESSIONS);

        assertThat(columns)
            .extracting(ColumnUsage::name, ColumnUsage::primaryKey, ColumnUsage::foreignKey,
                ColumnUsage::autoIncrement, ColumnUsage::roles, ColumnUsage::queries)
            .containsExactly(
                tuple("id", true, false, true, List.of(), 0),
                // used as a join column in q1 and a criteria column in q2
                tuple("user_id", false, true, false, List.of("criteria", "join"), 2),
                tuple("status", false, false, false, List.of("criteria"), 1),
                tuple("token", false, false, false, List.of(), 0));
        // users.email belongs to the other table and must not leak in
        assertThat(columns).extracting(ColumnUsage::name).doesNotContain("email");
        assertThat(columns.get(2).characterSet()).isEqualTo("utf8mb4");
        assertThat(columns.get(2).nullable()).isTrue();
    }

    @Test
    void statsSplitReadAndWriteByExecutions() {
        when(sqlRepositoryService.getTableQueries(SESSIONS)).thenAnswer(inv -> Stream.of(
            profile("read", metrics(100, 500), List.of(), List.of()),
            profile("write", metrics(50, 250), List.of(), List.of()),
            profile("unmetered", null, List.of(), List.of())));

        TableStats stats = service.getStats(SESSIONS);

        assertThat(stats.distinctQueries()).isEqualTo(3);
        assertThat(stats.totalExecutions()).isEqualTo(150);
        assertThat(stats.totalTimeMs()).isCloseTo(750, within(0.001));
        assertThat(stats.readExecutions()).isEqualTo(100);
        assertThat(stats.writeExecutions()).isEqualTo(50);
    }

    private static TableStructure structure() {
        TableIndex.Builder pk = TableIndex.builder().name("PRIMARY");
        pk.addColumn((short) 1, "id");
        ForeignKey fk = new ForeignKey.Builder()
            .addFromAndTo(criteria(SESSIONS, "user_id"), criteria(USERS, "id"))
            .build();
        return new TableStructure("app", "sessions", "TABLE", null,
            List.of(fk), List.of(pk.build()),
            List.of(
                column("id", "BIGINT", "NO", "YES", null, null),
                column("user_id", "INT", "NO", "NO", null, null),
                column("status", "VARCHAR", "YES", "NO", "utf8mb4", "utf8mb4_unicode_ci"),
                column("token", "VARCHAR", "NO", "NO", "utf8mb4", "utf8mb4_bin")));
    }

    private static TableColumn column(String name, String typeName, String nullable, String autoIncrement,
                                      String characterSet, String collation) {
        return new TableColumn("app", "sessions", name, JDBCType.VARCHAR, typeName, 20, 0, 10,
            null, null, 80, 1, nullable, autoIncrement, "NO", characterSet, collation);
    }

    private static ColumnReference criteria(TableReference table, String column) {
        return new ColumnReference(table, column, column);
    }

    private static JoinColumns join(TableReference leftTable, String leftColumn,
                                    TableReference rightTable, String rightColumn) {
        return new JoinColumns(criteria(leftTable, leftColumn), criteria(rightTable, rightColumn), "INNER");
    }

    private static RuntimeMetrics metrics(long executions, double totalMs) {
        return new RuntimeMetrics(false, executions, 0L, 0L,
            BigDecimal.valueOf(totalMs * 1_000_000_000L), BigDecimal.ONE, BigDecimal.ONE,
            0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
            "digest", null, null, BigDecimal.ONE, 0L, 0L, List.of());
    }

    private static SqlProfile profile(String id, RuntimeMetrics metrics,
                                      List<ColumnReference> criteriaColumns, List<JoinColumns> joins) {
        SqlProfile profile = new SqlProfile();
        profile.setId(id);
        profile.setQuery("select 1");
        profile.setType(metrics != null && id.equals("write") ? "UPDATE" : "SELECT");
        profile.setIsDml(id.equals("write"));
        profile.setDigest(id);
        profile.setNormalizedDigest(id);
        profile.setSchemaName("app");
        profile.setQueryActivityStatus(QueryActivityStatus.UNKNOWN);
        profile.setAnalysisFailed(false);
        profile.setRuntimeMetrics(metrics);
        profile.setTables(List.of(SESSIONS));
        profile.setCriteriaColumns(criteriaColumns);
        profile.setJoinColumns(joins);
        profile.setMismatchedJoins(List.of());
        return profile;
    }
}
