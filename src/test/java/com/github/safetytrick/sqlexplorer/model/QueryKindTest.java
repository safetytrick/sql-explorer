package com.github.safetytrick.sqlexplorer.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What a statement does, from its type and, when that is unknown, its rows affected (sqlx-a9h). */
class QueryKindTest {

    @Test
    void writesAreTheFourWritingOperations() {
        for (String type : new String[] {"INSERT", "UPDATE", "DELETE", "REPLACE", "update"}) {
            assertThat(QueryKind.of(type, 0)).as(type).isEqualTo(QueryKind.WRITE);
        }
    }

    @Test
    void aSelectReads() {
        assertThat(QueryKind.of("SELECT", 0)).isEqualTo(QueryKind.READ);
        assertThat(QueryKind.of("SELECT", 0).isRead()).isTrue();
    }

    /** A SELECT affects no rows, so any means a write; none proves nothing either way. */
    @Test
    void anUnknownStatementWritesOnlyIfItChangedRows() {
        assertThat(QueryKind.of("UNKNOWN", 3)).isEqualTo(QueryKind.WRITE);
        assertThat(QueryKind.of("UNKNOWN", 0)).isEqualTo(QueryKind.UNKNOWN);
        assertThat(QueryKind.of((String) null, 0)).isEqualTo(QueryKind.UNKNOWN);
    }

    /** Rows affected only decides for an unknown type: a CALL that wrote is still other. */
    @Test
    void anythingElseIsOtherWhateverItChanged() {
        assertThat(QueryKind.of("CALL", 3)).isEqualTo(QueryKind.OTHER);
        assertThat(QueryKind.of("SET", 0)).isEqualTo(QueryKind.OTHER);
        assertThat(QueryKind.of("CREATE", 0)).isEqualTo(QueryKind.OTHER);
    }

    /** Types written before analyzer version 8, until their next load re-types them. */
    @Test
    void typesFromBeforeCtesWereReadPast() {
        assertThat(QueryKind.of("(", 0)).isEqualTo(QueryKind.READ);
        assertThat(QueryKind.of("WITH", 0)).isEqualTo(QueryKind.UNKNOWN);
        assertThat(QueryKind.of("WITH", 2)).isEqualTo(QueryKind.WRITE);
    }
}
