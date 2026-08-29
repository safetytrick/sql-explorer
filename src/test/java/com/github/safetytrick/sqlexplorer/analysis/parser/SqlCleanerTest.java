package com.github.safetytrick.sqlexplorer.analysis.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SqlCleanerTest {

    @Test
    void remove_dangling_where() {
        String sql = """
                SELECT
                    `member_plan` . `id` ,
                    `member_plan` . `member_id` AS `memberId` ,
                    `member_plan` . `branch_id` AS `branchId` ,
                    `member_plan` . `group_id` AS `groupId` ,
                    `member_plan` . `group_name` AS `groupName` ,
                    `member_plan` . `last_seen` AS `lastSeen` ,
                    `member_plan` . `line_of_business` AS `lineOfBusiness` ,
                    `member_plan` . `account_number` AS `accountNumber` ,
                    `member_plan` . `status` ,
                    `member_plan` . `policy_number_suffix` AS `policyNumberSuffix` ,
                    `member_plan` . `service_start` AS `serviceStart` ,
                    `member_plan` . `service_end` AS `serviceEnd` ,
                    `member_plan` . `provider_id` AS `providerId` ,
                    `member_plan` . `provider` ,
                    `member_plan` . `remote_identifier` AS `remoteIdentifier` ,
                    `member_plan` . `data_source` AS `dataSourceId`
                FROM
                    `member_session_plan`
                JOIN
                    `member_plan`
                        ON `member_session_plan` . `member_plan_id` = `member_plan` . `id`
                WHERE
                    \
            """;

        assertFalse(new SqlCleaner().cleanSql(sql).sql().contains("WHERE"));
    }

    @Test
    void collapse_in_question_mark() {
        String sql = """
                SELECT
                    *
                FROM
                    `branch_identifier`
                WHERE
                    `identifier` IN (
                        ? ? ? ? ? ? ? ? ? ? ? ? ? ? ?
                    ) \
            """;

        SqlCleaner.CleanedSql cleanedSql = new SqlCleaner().cleanSql(sql);
        assertFalse(cleanedSql.sql().matches(".*\\?\\s+\\?"));
        assertTrue(cleanedSql.modified());
        assertFalse(cleanedSql.truncated());
    }

    @Test
    void collapse_if_statements() {
        String sql = "SELECT `pal` . `accessed_at` AS `viewed_branch_id` , `f` . `type` AS ? , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) AS ? , `f` . `state` AS ? , `f` . `zip` AS ? , `DATE_FORMAT` ( `pal` . `accessed_on` , ? ) AS ? , COUNT ( DISTINCTROW `pal` . `id` ) ? , COUNT ( DISTINCTROW `pal`";

        SqlCleaner.CleanedSql cleanedSql = new SqlCleaner().cleanSql(sql);

        assertTrue(cleanedSql.modified());
        assertEquals(cleanedSql.sql().indexOf("IF ( `f` . `type` = ?"),
                cleanedSql.sql().lastIndexOf("IF ( `f` . `type` = ?"),
                "only one instance should be left");
    }


    // ── MySQL 8.0.19 row aliases (sqlx-a1i) ──

    /**
     * Verbatim from the corpus, whitespace and all: the shape that accounted
     * for eight of the corpus's parse failures and every user-schema statement
     * the analyzer genuinely lost.
     */
    private static final String ROW_ALIAS_INSERT = """
                INSERT
                INTO
                    `search`
                    . `user_info` (
                        `last_name` , `full_name` , `email` , `id`
                    )
                VALUES
                    (...) AS NEW
                        ON DUPLICATE KEY UPDATE `last_name` = `new` . `last_name` , `full_name` = `new` . `full_name` , `email` = `new` . `email`
            """;

    @Test
    void the_row_alias_insert_parses_once_cleaned() throws Exception {
        SqlParser.CleanedStatement parsed = new SqlParser().parseSql(ROW_ALIAS_INSERT);

        assertNotNull(parsed.statement());
        assertTrue(parsed.modified(), "the rewrite has to be reported as a modification");
    }

    /**
     * The point of rewriting rather than deleting. Dropping `AS NEW` alone
     * leaves `= `new`.`col`` behind, which parses — and then the analyzers
     * resolve `new` as a table that does not exist.
     */
    @Test
    void the_alias_leaves_no_phantom_table_behind() {
        String cleaned = new SqlCleaner().cleanSql(ROW_ALIAS_INSERT).sql();

        assertFalse(cleaned.toLowerCase().contains("as new"), cleaned);
        assertFalse(cleaned.contains("`new`"), cleaned);
        assertTrue(cleaned.contains("VALUES(`last_name`)"), cleaned);
    }

    /** The values list keeps its closing paren — the alias clause is all that goes. */
    @Test
    void the_values_list_survives_the_rewrite() {
        String cleaned = new SqlCleaner().cleanSql(ROW_ALIAS_INSERT).sql();

        assertTrue(cleaned.contains("( ? )") || cleaned.contains("(?)"), cleaned);
    }

    /** An INSERT with no alias is not touched, and neither is a plain AS. */
    @Test
    void leaves_the_classic_form_alone() {
        String classic = """
                INSERT INTO `t` ( `a` ) VALUES ( ? )
                    ON DUPLICATE KEY UPDATE `a` = VALUES ( `a` )
            """;

        assertEquals(classic.trim(), new SqlCleaner().cleanSql(classic).sql().trim());
    }

    /**
     * The alias is declared upper and referenced lower in every corpus
     * example, so the reference rewrite has to be case-insensitive.
     */
    @Test
    void matches_the_alias_whatever_case_it_is_referenced_in() {
        String mixed = "INSERT INTO `t` ( `a` ) VALUES ( ? ) AS Fresh "
            + "ON DUPLICATE KEY UPDATE `a` = `FRESH` . `a`";

        String cleaned = new SqlCleaner().cleanSql(mixed).sql();

        assertTrue(cleaned.contains("VALUES(`a`)"), cleaned);
        assertFalse(cleaned.toLowerCase().contains("fresh"), cleaned);
    }
}
