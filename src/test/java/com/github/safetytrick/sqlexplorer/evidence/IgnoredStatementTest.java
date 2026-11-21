package com.github.safetytrick.sqlexplorer.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void rollbackToSavepointWasAlreadyCoveredByRollback() {
        assertThat(ignored("ROLLBACK TO SAVEPOINT `SVP0`")).isTrue();
        assertThat(ignored("ROLLBACK")).isTrue();
    }

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
