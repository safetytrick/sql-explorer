package com.github.safetytrick.sqlexplorer.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void anUnknownStatementWritesOnlyIfItChangedRows() {
        assertThat(QueryKind.of("UNKNOWN", 3)).isEqualTo(QueryKind.WRITE);
        assertThat(QueryKind.of("UNKNOWN", 0)).isEqualTo(QueryKind.UNKNOWN);
        assertThat(QueryKind.of((String) null, 0)).isEqualTo(QueryKind.UNKNOWN);
    }

    @Test
    void anythingElseIsOtherWhateverItChanged() {
        assertThat(QueryKind.of("CALL", 3)).isEqualTo(QueryKind.OTHER);
        assertThat(QueryKind.of("SET", 0)).isEqualTo(QueryKind.OTHER);
        assertThat(QueryKind.of("CREATE", 0)).isEqualTo(QueryKind.OTHER);
    }

    @Test
    void typesFromBeforeCtesWereReadPast() {
        assertThat(QueryKind.of("(", 0)).isEqualTo(QueryKind.READ);
        assertThat(QueryKind.of("WITH", 0)).isEqualTo(QueryKind.UNKNOWN);
        assertThat(QueryKind.of("WITH", 2)).isEqualTo(QueryKind.WRITE);
    }
}
