package com.github.safetytrick.sqlexplorer.profiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryService;
import com.github.safetytrick.sqlexplorer.profiles.CoQueriedService.CoQueriedTable;
import com.github.safetytrick.sqlexplorer.schema.ForeignKey;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaService;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoQueriedServiceTest {

    private static final TableReference SESSIONS = ref("app", "sessions");
    private static final TableReference USERS = ref("app", "users");
    private static final TableReference ACCOUNTS = ref("app", "accounts");

    private SqlRepositoryService sqlRepositoryService;
    private SqlSchemaService sqlSchemaService;
    private CoQueriedService service;

    @BeforeEach
    void setUp() {
        sqlRepositoryService = mock(SqlRepositoryService.class);
        sqlSchemaService = mock(SqlSchemaService.class);
        service = new CoQueriedService(sqlRepositoryService, sqlSchemaService);
        when(sqlSchemaService.getTable(any())).thenReturn(null);
    }

    @Test
    void aggregatesPartnersAcrossQueriesSortedByExecutions() {
        when(sqlRepositoryService.getTableQueries(SESSIONS)).thenAnswer(inv -> Stream.of(
            profile("q1", metrics(100, 200), List.of(SESSIONS, USERS), join(SESSIONS, USERS)),
            profile("q2", metrics(300, 900), List.of(SESSIONS, USERS, ACCOUNTS)),
            // self-join: sessions twice, partner counted once
            profile("q3", metrics(5, 10), List.of(SESSIONS, SESSIONS, ACCOUNTS))));

        List<CoQueriedTable> partners = service.getCoQueried(SESSIONS);

        assertThat(partners)
            .extracting(CoQueriedTable::table, CoQueriedTable::queries, CoQueriedTable::executions, CoQueriedTable::joined)
            .containsExactly(
                tuple("users", 2, 400L, true),
                tuple("accounts", 2, 305L, false));
        // 1100ms total over 400 executions, converted from picoseconds
        assertThat(partners.getFirst().avgTimeMs()).isCloseTo(2.75, within(0.001));
    }

    @Test
    void foreignKeyDirectionComesFromEitherSideOfThePair() {
        when(sqlRepositoryService.getTableQueries(SESSIONS)).thenAnswer(inv -> Stream.of(
            profile("q1", metrics(1, 1), List.of(SESSIONS, USERS, ACCOUNTS))));
        // sessions.user_id -> users.id : "out" from sessions' perspective
        when(sqlSchemaService.getTable(SESSIONS)).thenReturn(structureWithFkTo(SESSIONS, USERS));
        // accounts.session_id -> sessions.id : "in" from sessions' perspective
        when(sqlSchemaService.getTable(ACCOUNTS)).thenReturn(structureWithFkTo(ACCOUNTS, SESSIONS));

        assertThat(service.getCoQueried(SESSIONS))
            .extracting(CoQueriedTable::table, CoQueriedTable::fkDirection)
            .containsExactlyInAnyOrder(tuple("users", "out"), tuple("accounts", "in"));
    }

    private static TableReference ref(String schema, String table) {
        return new TableReference(schema, table, table, schema);
    }

    private static JoinColumns join(TableReference left, TableReference right) {
        return new JoinColumns(new ColumnReference(left, "id", "id"), new ColumnReference(right, "id", "id"));
    }

    private static TableStructure structureWithFkTo(TableReference from, TableReference to) {
        ForeignKey fk = new ForeignKey.Builder()
            .addFromAndTo(new ColumnReference(from, "x", "x"), new ColumnReference(to, "id", "id"))
            .build();
        return new TableStructure(from.getSchemaName(), from.getTableName(), "TABLE", null,
            List.of(fk), List.of(), List.of());
    }

    private static RuntimeMetrics metrics(long executions, double totalMs) {
        return new RuntimeMetrics(false, executions, 0L, 0L,
            BigDecimal.valueOf(totalMs * 1_000_000_000L), BigDecimal.ONE, BigDecimal.ONE,
            0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
            "digest", null, null, BigDecimal.ONE, 0L, 0L, List.of());
    }

    private static SqlProfile profile(String id, RuntimeMetrics metrics, List<TableReference> tables,
                                      JoinColumns... joins) {
        SqlProfile profile = new SqlProfile();
        profile.setId(id);
        profile.setQuery("select 1");
        profile.setType("SELECT");
        profile.setDigest(id);
        profile.setNormalizedDigest(id);
        profile.setSchemaName("app");
        profile.setQueryActivityStatus(QueryActivityStatus.UNKNOWN);
        profile.setAnalysisFailed(false);
        profile.setRuntimeMetrics(metrics);
        profile.setTables(tables);
        profile.setJoinColumns(List.of(joins));
        profile.setMismatchedJoins(List.of());
        return profile;
    }
}
