package com.github.safetytrick.sqlexplorer.analysis.parser;

import org.junit.jupiter.api.Test;

import static com.github.safetytrick.sqlexplorer.analysis.parser.RunFinder.reformatQuery;
import static org.assertj.core.api.Assertions.assertThat;

class RunFinderTest {

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    @Test
    void monster_sql() {
        String sql = """
            SELECT
                    `some_search` . `name` AS `name` ,
                    `some_search` . `uuid` AS `uuid` ,
                    `some_search` . `org_id` AS `org_id` ,
                    `some_search` . `weight` AS `weight` ,
                    `some_search` . `is_valid` AS `is_valid` ,
                    `some_search` . `foo_type` AS `foo_type` ,
                    `some_search` . `uuid_type` AS `uuid_type` ,
                    `some_search` . `created_by` AS `created_by` ,
                    `some_search` . `is_deleted` AS `is_deleted` ,
                    `some_search` . `created_at` AS `created_at` ,
                    `some_search` . `updated_at` AS `updated_at` ,
                    `some_search` . `foo_owner` AS `foo_owner` ,
                    `some_search` . `entity_type` AS `entity_type` ,
                    `some_search` . `usage_count` AS `usage_count` ,
                    `some_search` . `is_archived` AS `is_archived` ,
                    `some_search` . `description` AS `description` ,
                    `some_search` . `is_published` AS `is_published` ,
                    `some_search` . `current_rank` AS `current_rank` ,
                    `some_search` . `foo_language` AS `foo_language` ,
                    `some_search` . `foo_platform` AS `foo_platform` ,
                    `some_search` . `external_title` AS `external_title` ,
                    `some_search` . `foo_owner_name` AS `foo_owner_name` ,
                    `some_search` . `created_by_name` AS `created_by_name` ,
                    `some_search` . `fooboard_count` AS `fooboard_count` ,
                    `some_search` . `public_link_count` AS `public_link_count` ,
                    `some_search` . `visible_in_library` AS `visible_in_library`\s
                FROM
                    `some_search`\s
                WHERE
                    `some_search` . `org_id` = ?\s
                    AND `some_search` . `uuid_type` IN (...)\s
                    AND (
                        (
                            `some_search` . `is_deleted` = ?\s
                            AND `some_search` . `entity_type` = ?\s
                        )\s
                        AND (
                            (
                                `UPPER` (
                                    `some_search` . `name`\s
                                ) LIKE `UPPER` (
                                    ?
                                )\s
                                AND `UPPER` (
                                    `some_search` . `name`\s
                                ) LIKE `UPPER` (
                                    ?
                                )\s
                                AND `UPPER` (
                                    `some_search` . `name`\s
                                ) LIKE `UPPER` (
                                    ?
                                )\s
                                AND `UPPER` (
                                    `some_search` . `name`\s
                                ) LIKE `UPPER` (
                                    ?
                                )\s
                                AND `UPPER` (
                                    `some_search` . `name`\s
                                ) LIKE `UPPER` (
                                    ?
                                )\s
                                AND `UPPER` (
                                    `some_search` . `name`\s
                                ) LIKE `UPPER` (
                                    ?
                                )\s
                                AND `UPPER` (
                                    `some_search` . `name`\s
                                ) LIKE `UPPER` (
                                    ?
                                )\s
                                AND `UPPER` (
                                    `some_search` . `name`\s
                                ) LIKE `UPPER` (
                                    ?
                                )\s
                                AND `UPPER` (
                                    `some_search` . `name`\s
                                ) LIKE `UPPER` (
                                    ?
                                )\s
                                AND `UPPER` (
                                    `some_search` . `name`\s
                                ) LIKE `UPPER` (
                                    ?
                                )\s
                                OR (
                                    `UPPER` (
                                        `some_search` . `description`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                )\s
                                OR (
                                    `UPPER` (
                                        `some_search` . `foo_owner_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `some_search` . `foo_owner_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `some_search` . `foo_owner_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `some_search` . `foo_owner_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `some_search` . `foo_owner_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `some_search` . `foo_owner_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `some_search` . `foo_owner_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `some_search` . `foo_owner_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `some_search` . `foo_owner_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `some_search` . `foo_owner_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                )\s
                                OR (
                                    `UPPER` (
                                        `some_search` . `created_by_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `some_search` . `created_by_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `some_search` . `created_by_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `some_search` . `created_by_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `some_search` . `created_by_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `some_search` . `created_by_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `some_search` . `created_by_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `some_search` . `created_by_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `some_search` . `created_by_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `some_search` . `created_by_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                )\s
                            )\s
                            AND (
                                `some_search` . `created_by` IN (...)\s
                            )\s
                            AND (
                                `some_search` . `created_at` BETWEEN ? AND ?\s
                            )\s
                            AND (
                                `some_search` . `is_archived` = ?\s
                            )\s
                            AND (
                                `some_search` . `is_valid` = ?\s
                            )\s
                            AND (
                                `some_search` . `foo_type` IN (...)\s
                            )\s
                            AND (
                                `some_search` . `foo_platform` IN (...)\s
                            )\s
                            AND (
                                `some_search` . `foo_language` IN (...)\s
                                OR `some_search` . `foo_language` IS NULL\s
                            )\s
                            AND (
                                `some_search` . `foo_owner` IN (...)\s
                            )\s
                            AND (
                                `some_search` . `is_published` = ?\s
                                OR `some_search` . `is_published` IS NULL\s
                            )\s
                        )\s
                    )\s
                ORDER BY
                    `some_search` . `current_rank` DESC
            """;

        String result = reformatQuery(sql);
        System.out.println(result);

        // The WHERE clause has three blocks of ten identical `AND UPPER(col) LIKE UPPER(?)`
        // predicates (name, foo_owner_name, created_by_name). Each collapses to one copy
        // plus a `...*[9]` marker for the nine dropped copies.
        assertThat(count(result, "...*[9]")).isEqualTo(3);
        // Collapsing dropped the bulk of the repeated LIKE predicates.
        assertThat(count(result, "LIKE")).isLessThan(count(sql, "LIKE"));
    }

    @Test
    void another_monster() {
        String query = """
                SELECT
                    `TABLE_SCHEMA` ,
                    ? ,
                    TABLE_NAME ,
                    COLUMN_NAME ,
                    CASE\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN IF ( `LOCATE` ( ? , `UPPER` ( `COLUMN_TYPE` ) ) = ?\s
                            AND `LOCATE` ( ? , `UPPER` ( `COLUMN_TYPE` ) ) = ?\s
                            AND `LOCATE` ( ? , `COLUMN_TYPE` ) != ?, ... )\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN IF ( `LOCATE` ( ? , `UPPER` ( `COLUMN_TYPE` ) ) = ?\s
                            AND `LOCATE` ( ? , `UPPER` ( `COLUMN_TYPE` ) ) = ?\s
                            AND `LOCATE` ( ? , `COLUMN_TYPE` ) != ?, ... )\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN - ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN - ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN - ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN - ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN - ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN - ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN - ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN - ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN - ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN - ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN - ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN - ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN - ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN - ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN - ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN - ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN - ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN - ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN - ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN - ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN - ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN - ?\s
                        WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                            THEN - ?\s
                        ELSE ?\s
                END AS `DATA_TYPE` ,
                `UPPER` ( CASE\s
                    WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                        THEN CASE\s
                            WHEN `LOCATE` ( ? ,
                            `UPPER` ( `COLUMN_TYPE` ) ) = ?\s
                            AND `LOCATE` ( ? ,
                            `UPPER` ( `COLUMN_TYPE` ) ) = ?\s
                            AND `LOCATE` ( ? ,
                            `COLUMN_TYPE` ) != ?\s
                                THEN ?\s
                            WHEN `LOCATE` ( ? ,
                            `UPPER` ( `COLUMN_TYPE` ) ) != ?\s
                            AND `LOCATE` ( ? ,
                            `UPPER` ( `DATA_TYPE` ) ) = ?\s
                                THEN ?\s
                            ELSE `DATA_TYPE`\s
                    END\s
                WHEN `LOCATE` ( ? ,
                `UPPER` ( `COLUMN_TYPE` ) ) != ?\s
                AND `LOCATE` ( ? ,
                `UPPER` ( `DATA_TYPE` ) ) = ?\s
                AND `LOCATE` ( ? ,
                `UPPER` ( `DATA_TYPE` ) ) != ?\s
                AND `LOCATE` ( ? ,
                `UPPER` ( `DATA_TYPE` ) ) != ?\s
                    THEN `CONCAT` ( `DATA_TYPE` ,
                    ? )\s
                WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                    THEN ?\s
                WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                    THEN ?\s
                WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                    THEN ?\s
                WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                    THEN ?\s
                WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                    THEN ?\s
                WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                    THEN ?\s
                WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                    THEN ?\s
                WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                    THEN ?\s
                ELSE `UPPER` ( `DATA_TYPE` )\s
            END ) AS `TYPE_NAME` ,
            `UPPER` ( CASE\s
                WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                    THEN ?\s
                WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                    THEN ?\s
                WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                OR `UPPER` ( `DATA_TYPE` ) = ?\s
                    THEN ? + IF ( `DATETIME_PRECISION` > ? , `DATETIME_PRECISION` + ? , `DATETIME_PRECISION` )\s
                WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                    THEN ? + IF ( `DATETIME_PRECISION` > ? , `DATETIME_PRECISION` + ? , `DATETIME_PRECISION` )\s
                WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                AND `LOCATE` ( ? ,
                `UPPER` ( `COLUMN_TYPE` ) ) = ?\s
                AND `LOCATE` ( ? ,
                `UPPER` ( `COLUMN_TYPE` ) ) = ?\s
                AND `LOCATE` ( ? ,
                `COLUMN_TYPE` ) != ?\s
                    THEN ?\s
                WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                AND `LOCATE` ( ? ,
                `UPPER` ( `COLUMN_TYPE` ) ) != ?\s
                    THEN ?\s
                WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                    THEN ?\s
                WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                    THEN ?\s
                WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                    THEN ?\s
                WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                    THEN ?\s
                WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                    THEN ?\s
                WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                    THEN ?\s
                WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                    THEN ?\s
                WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                    THEN ?\s
                WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                    THEN ?\s
                WHEN `UPPER` ( `DATA_TYPE` ) = ?\s
                    THEN ?\s
                WHEN
            """;

        String result = reformatQuery(query);
        System.out.println(result);

        // The long run of identical `WHEN UPPER(DATA_TYPE) = ? THEN ?` clauses is the
        // most rewarding run to collapse and must be picked: 11 copies -> one + `...*[11]`.
        // (The previous covered()-based ranking instead picked a 2-copy giant block,
        // which spans more tokens but saves fewer, and fragmented this run.)
        assertThat(result).contains("...*[11]");
        // Collapsing dropped most of the repeated WHEN clauses.
        assertThat(count(result, "WHEN")).isLessThan(count(query, "WHEN"));
    }

}