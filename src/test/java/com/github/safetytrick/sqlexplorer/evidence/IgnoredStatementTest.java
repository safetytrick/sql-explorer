package com.github.safetytrick.sqlexplorer.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which statements are classified as not-worth-parsing.
 *
 * <p>This looks like a list-membership test and is really about a report.
 * A statement absent from {@code ignoredFirstTokens} that also fails to parse
 * lands in the parse-failures report and behind the workbench's
 * {@code analFail} filter — both of which exist to answer "what should have
 * been analysed and was not". Measured against the corpus on 2026-08-29, 25 of
 * 35 parse failures were savepoint and purge statements: noise outnumbering
 * the real failures 3:1 and burying them (sqlx-96y).
 */
class IgnoredStatementTest {

    private static boolean ignored(String sql) {
        return SqlEvidenceInputService.ignoredFirstTokens.contains(
            SqlEvidenceInputService.firstToken(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SAVEPOINT `DOCTRINE_2`",
        "SAVEPOINT `a2d1f0a3_cb63_4255_9c0b_51c1`",
        "RELEASE SAVEPOINT `DOCTRINE_2`",
        "RELEASE SAVEPOINT `SVP2`",
        "PURGE BINARY LOGS BEFORE ?"
    })
    void transactionAndAdminControlIsNotWorthParsing(String sql) {
        assertThat(ignored(sql)).as(sql).isTrue();
    }

    /** Already covered, because the match is on the first token alone. */
    @Test
    void rollbackToSavepointWasAlreadyCoveredByRollback() {
        assertThat(ignored("ROLLBACK TO SAVEPOINT `SVP0`")).isTrue();
        assertThat(ignored("ROLLBACK")).isTrue();
    }

    /**
     * The guard that matters. Everything this list touches is dropped from
     * analysis outright, so a DML keyword reaching it would silently blind the
     * whole app to real queries.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT `a` FROM `t`",
        "INSERT INTO `t` ( `a` ) VALUES ( ? )",
        "UPDATE `t` SET `a` = ?",
        "DELETE FROM `t` WHERE `a` = ?",
        "REPLACE INTO `t` ( `a` ) VALUES ( ? )",
        "WITH `c` AS ( SELECT ? ) SELECT * FROM `c`"
    })
    void statementsThatCarryColumnsAreNeverIgnored(String sql) {
        assertThat(ignored(sql)).as(sql).isFalse();
    }

    @Test
    void theTokenIsReadCaseInsensitivelyAndPastLeadingWhitespace() {
        assertThat(ignored("\n    release savepoint `x`")).isTrue();
    }
}
