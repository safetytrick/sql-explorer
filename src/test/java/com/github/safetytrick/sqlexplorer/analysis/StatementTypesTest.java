package com.github.safetytrick.sqlexplorer.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A statement's type from its text alone: the operation behind any CTEs or
 * opening parenthesis, without parsing (sqlx-a9h).
 */
class StatementTypesTest {

    private static String type(String sql) {
        return StatementTypes.fromText(sql);
    }

    @Test
    void aStatementThatOpensWithItsOperationIsThatOperation() {
        assertThat(type("SELECT id FROM account")).isEqualTo("SELECT");
        assertThat(type("update account set status = ?")).isEqualTo("UPDATE");
        assertThat(type("REPLACE INTO account VALUES (?)")).isEqualTo("REPLACE");
        assertThat(type("SET NAMES utf8mb4")).isEqualTo("SET");
        assertThat(type("COMMIT")).isEqualTo("COMMIT");
    }

    @Test
    void aCteListIsReadPastToTheOperationItFeeds() {
        assertThat(type("WITH a AS (SELECT id FROM account) SELECT id FROM a")).isEqualTo("SELECT");
        assertThat(type("WITH a AS (SELECT id FROM account) UPDATE account SET status = ?")).isEqualTo("UPDATE");
        assertThat(type("WITH a AS (SELECT id FROM account) DELETE FROM account")).isEqualTo("DELETE");
    }

    /** Performance Schema's digest text: backticked names, spaced punctuation. */
    @Test
    void digestTextIsReadTheSameWay() {
        assertThat(type("WITH `stale` AS ( SELECT `id` FROM `account` WHERE `status` = ? ) "
            + "UPDATE `account` `a` JOIN `stale` `s` ON `s` . `id` = `a` . `id` SET `a` . `status` = ?"))
            .isEqualTo("UPDATE");
    }

    @Test
    void recursiveCtesColumnListsAndSeveralCtes() {
        assertThat(type("WITH RECURSIVE n (i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 5) SELECT i FROM n"))
            .isEqualTo("SELECT");
        assertThat(type("WITH a AS (SELECT id FROM account), b (id, n) AS (SELECT id, count(*) FROM site GROUP BY id) "
            + "DELETE FROM site WHERE id IN (SELECT id FROM b)")).isEqualTo("DELETE");
    }

    /** Parentheses inside a body, a string or a quoted name do not end it. */
    @Test
    void onlyTopLevelParenthesesCount() {
        assertThat(type("WITH a AS (SELECT id FROM account WHERE (status IN ('x)', ?)) AND id > (SELECT 1)) "
            + "UPDATE account SET status = ?")).isEqualTo("UPDATE");
        assertThat(type("WITH `odd)name` AS (SELECT 1) SELECT * FROM `odd)name`")).isEqualTo("SELECT");
        assertThat(type("WITH a AS (SELECT 'it''s (' AS s) SELECT s FROM a")).isEqualTo("SELECT");
        assertThat(type("WITH a AS (SELECT 'it\\'s (' AS s) SELECT s FROM a")).isEqualTo("SELECT");
    }

    @Test
    void commentsAreSkipped() {
        assertThat(type("WITH a AS (SELECT 1 /* ) */) -- )\n DELETE FROM account")).isEqualTo("DELETE");
    }

    @Test
    void aParenthesisedQueryIsASelect() {
        assertThat(type("( SELECT id FROM account ) UNION ( SELECT id FROM site )")).isEqualTo("SELECT");
        assertThat(type("((SELECT id FROM account))")).isEqualTo("SELECT");
        assertThat(type("( WITH a AS (SELECT 1) SELECT * FROM a )")).isEqualTo("SELECT");
        assertThat(type("WITH a AS (SELECT 1) (SELECT * FROM a)")).isEqualTo("SELECT");
    }

    /** Truncated by Performance Schema before the operation: there is nothing to read it from. */
    @Test
    void textThatStopsBeforeTheOperationIsUnknown() {
        assertThat(type("WITH a AS ( SELECT `id` FROM `account` WHERE `status` = ? AND ...")).isEqualTo("UNKNOWN");
        assertThat(type("WITH a AS (SELECT 1)")).isEqualTo("UNKNOWN");
        assertThat(type("WITH a AS (SELECT 1), b AS (SELECT")).isEqualTo("UNKNOWN");
        assertThat(type("( SELEC")).isEqualTo("UNKNOWN");
    }

    @Test
    void whatIsNotACteListIsUnknown() {
        assertThat(type("WITH a SELECT 1")).isEqualTo("UNKNOWN");
        assertThat(type("WITH a AS (SELECT 1) INSERT INTO account VALUES (1)")).isEqualTo("UNKNOWN");
        assertThat(type("( UPDATE account SET status = ? )")).isEqualTo("UNKNOWN");
        assertThat(type(null)).isEqualTo("UNKNOWN");
        assertThat(type("   ")).isEqualTo("UNKNOWN");
    }
}
