package com.github.safetytrick.sqlexplorer.schema;

import java.sql.JDBCType;
import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.schema.ForeignKeyDetailService.ForeignKeyColumnDetail;
import com.github.safetytrick.sqlexplorer.schema.ForeignKeyDetailService.ForeignKeyDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ForeignKeyDetailServiceTest {

    private static final TableReference SESSIONS = TableReference.newTableReference("app", "sessions");
    private static final TableReference USERS = TableReference.newTableReference("app", "users");
    private static final TableReference EVENTS = TableReference.newTableReference("app", "events");

    private SqlSchemaService sqlSchemaService;
    private ForeignKeyDetailService service;

    @BeforeEach
    void setUp() {
        sqlSchemaService = mock(SqlSchemaService.class);
        service = new ForeignKeyDetailService(sqlSchemaService);

        // sessions.user_id (INT) -> users.id (BIGINT); events.session_id -> sessions.id
        TableStructure sessions = structure(SESSIONS,
            List.of(fk("fk_sessions_user", SESSIONS, "user_id", USERS, "id")),
            column("id", "BIGINT", 19, null), column("user_id", "INT", 10, null));
        TableStructure users = structure(USERS, List.of(),
            column("id", "BIGINT", 19, null));
        TableStructure events = structure(EVENTS,
            List.of(fk("fk_events_session", EVENTS, "session_id", SESSIONS, "id")),
            column("session_id", "BIGINT", 19, null));

        when(sqlSchemaService.getTable(SESSIONS)).thenReturn(sessions);
        when(sqlSchemaService.getTable(USERS)).thenReturn(users);
        when(sqlSchemaService.getTable(EVENTS)).thenReturn(events);
        when(sqlSchemaService.getTables()).thenReturn(List.of(sessions, users, events));
    }

    @Test
    void listsOutgoingAndIncomingForeignKeys() {
        List<ForeignKeyDetail> details = service.getForeignKeys(SESSIONS);

        assertThat(details)
            .extracting(ForeignKeyDetail::direction, ForeignKeyDetail::constraintName, ForeignKeyDetail::otherTable)
            .containsExactly(
                tuple("out", "fk_sessions_user", "users"),
                tuple("in", "fk_events_session", "events"));
        assertThat(details.getFirst().onUpdate()).isEqualTo("CASCADE");
        assertThat(details.getFirst().onDelete()).isEqualTo("RESTRICT");
    }

    @Test
    void flagsStrictTypeMismatchPerColumnPair() {
        List<ForeignKeyDetail> details = service.getForeignKeys(SESSIONS);

        ForeignKeyColumnDetail mismatched = details.getFirst().columns().getFirst();
        assertThat(mismatched.fromType()).isEqualTo("INT(10)");
        assertThat(mismatched.toType()).isEqualTo("BIGINT(19)");
        assertThat(mismatched.typeMismatch()).isTrue();

        ForeignKeyColumnDetail matched = details.get(1).columns().getFirst();
        assertThat(matched.typeMismatch()).isFalse();
    }

    /**
     * In a many-server environment every server's copy of a table is in
     * getTables(), so the incoming scan met each referencing constraint once
     * per server — fifteen copies of each on production. One per constraint,
     * and the first server's definition is the one shown.
     */
    @Test
    void aConstraintSeenOnSeveralServersIsListedOnceAsTheFirstServerDefinedIt() {
        TableStructure sessions = sqlSchemaService.getTable(SESSIONS);
        TableStructure users = sqlSchemaService.getTable(USERS);
        TableStructure events = sqlSchemaService.getTable(EVENTS);
        // the second server's events defines the same constraint with other rules
        TableStructure eventsOnSecondServer = structure(EVENTS,
            List.of(new ForeignKey.Builder()
                .constraintName("fk_events_session")
                .rules("NO ACTION", "SET NULL")
                .addFromAndTo(ColumnReference.newColumnReference(EVENTS, "session_id"),
                    ColumnReference.newColumnReference(SESSIONS, "id"))
                .build()),
            column("session_id", "BIGINT", 19, null));
        when(sqlSchemaService.getTables()).thenReturn(
            List.of(sessions, users, events, sessions, users, eventsOnSecondServer));

        List<ForeignKeyDetail> details = service.getForeignKeys(SESSIONS);

        assertThat(details)
            .extracting(ForeignKeyDetail::direction, ForeignKeyDetail::constraintName, ForeignKeyDetail::onUpdate)
            .containsExactly(
                tuple("out", "fk_sessions_user", "CASCADE"),
                tuple("in", "fk_events_session", "CASCADE"));
    }

    /** Constraint names are unique per schema, not per server: the same name on two tables is two constraints. */
    @Test
    void theSameConstraintNameOnTwoDifferentTablesIsKeptTwice() {
        TableReference audits = TableReference.newTableReference("app", "audits");
        TableStructure auditsTable = structure(audits,
            List.of(fk("fk_events_session", audits, "session_id", SESSIONS, "id")),
            column("session_id", "BIGINT", 19, null));
        TableStructure sessions = sqlSchemaService.getTable(SESSIONS);
        TableStructure events = sqlSchemaService.getTable(EVENTS);
        when(sqlSchemaService.getTables()).thenReturn(List.of(sessions, events, auditsTable));

        assertThat(service.getForeignKeys(SESSIONS))
            .filteredOn(detail -> detail.direction().equals("in"))
            .extracting(ForeignKeyDetail::otherTable)
            .containsExactly("events", "audits");
    }

    private static TableStructure structure(TableReference table, List<ForeignKey> fks, TableColumn... columns) {
        return new TableStructure(table.getSchemaName(), table.getTableName(), "TABLE", null,
            fks, List.of(), List.of(columns), null, null);
    }

    private static ForeignKey fk(String name, TableReference fromTable, String fromColumn,
                                 TableReference toTable, String toColumn) {
        return new ForeignKey.Builder()
            .constraintName(name)
            .rules("CASCADE", "RESTRICT")
            .addFromAndTo(ColumnReference.newColumnReference(fromTable, fromColumn),
                ColumnReference.newColumnReference(toTable, toColumn))
            .build();
    }

    private static TableColumn column(String name, String typeName, int size, String characterSet) {
        return new TableColumn("app", "t", name, JDBCType.OTHER, typeName, size, 0, 10,
            null, null, 0, 1, "NO", "NO", "NO", characterSet, null);
    }
}
