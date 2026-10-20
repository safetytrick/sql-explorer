package com.github.safetytrick.sqlexplorer.profiles;

import java.math.BigDecimal;
import java.sql.JDBCType;
import java.util.List;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.IndexHint;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryService;
import com.github.safetytrick.sqlexplorer.profiles.TableInsightsService.ColumnUsage;
import com.github.safetytrick.sqlexplorer.profiles.TableInsightsService.IndexUsage;
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
    void mergesIndexMetadataWithHintUsage() {
        TableIndex.Builder byUser = TableIndex.builder().name("sessions_user_idx");
        byUser.addColumn((short) 1, "user_id");
        TableStructure structure = structure();
        structure = new TableStructure("app", "sessions", "TABLE", null, structure.foreignKeys(),
            List.of(structure.indices().getFirst(), byUser.build()), structure.columns(), null, null);
        when(sqlSchemaService.getTable(SESSIONS)).thenReturn(structure);
        SqlProfile forced = profile("q1", null, List.of(), List.of());
        forced.setIndexHints(List.of(hint(SESSIONS, "`Sessions_User_Idx`", "FORCE"), hint(SESSIONS, "PRIMARY", "IGNORE")));
        SqlProfile used = profile("q2", null, List.of(), List.of());
        // the same index named twice by one query counts that query once
        used.setIndexHints(List.of(hint(SESSIONS, "sessions_user_idx", "USE"), hint(SESSIONS, "sessions_user_idx", "FORCE"),
            hint(SESSIONS, "sessions_gone_idx", "FORCE"), hint(USERS, "users_email_idx", "FORCE")));
        when(sqlRepositoryService.getTableQueries(SESSIONS)).thenAnswer(inv -> Stream.of(forced, used,
            profile("q3", null, List.of(), List.of())));

        List<IndexUsage> indexes = service.getIndexes(SESSIONS);

        assertThat(indexes)
            .extracting(IndexUsage::name, IndexUsage::primary, IndexUsage::exists, IndexUsage::columns,
                IndexUsage::hints, IndexUsage::queries)
            .containsExactly(
                tuple("PRIMARY", true, true, List.of("id"), List.of("ignore"), 1),
                tuple("sessions_user_idx", false, true, List.of("user_id"), List.of("force", "use"), 2),
                // hinted, but the table has no such index: MySQL rejects the statement
                tuple("sessions_gone_idx", false, false, List.of(), List.of("force"), 1));
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
                column("token", "VARCHAR", "NO", "NO", "utf8mb4", "utf8mb4_bin")), null, null);
    }

    private static TableColumn column(String name, String typeName, String nullable, String autoIncrement,
                                      String characterSet, String collation) {
        return new TableColumn("app", "sessions", name, JDBCType.VARCHAR, typeName, 20, 0, 10,
            null, null, 80, 1, nullable, autoIncrement, "NO", characterSet, collation);
    }

    private static ColumnReference criteria(TableReference table, String column) {
        return new ColumnReference(table, column, column);
    }

    private static IndexHint hint(TableReference table, String index, String action) {
        return new IndexHint(table, index, index.replace("`", "").toLowerCase(java.util.Locale.ROOT), action);
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
