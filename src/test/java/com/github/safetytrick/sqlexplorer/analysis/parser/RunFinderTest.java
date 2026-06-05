package com.github.safetytrick.sqlexplorer.analysis.parser;

import org.junit.jupiter.api.Test;

import static com.github.safetytrick.sqlexplorer.analysis.parser.RunFinder.reformatQuery;

class RunFinderTest {

    @Test
    void monster_sql() {
        String sql = """
            SELECT
                    `demo_search_vw` . `name` AS `name` ,
                    `demo_search_vw` . `uuid` AS `uuid` ,
                    `demo_search_vw` . `org_id` AS `org_id` ,
                    `demo_search_vw` . `weight` AS `weight` ,
                    `demo_search_vw` . `is_valid` AS `is_valid` ,
                    `demo_search_vw` . `demo_type` AS `demo_type` ,
                    `demo_search_vw` . `uuid_type` AS `uuid_type` ,
                    `demo_search_vw` . `created_by` AS `created_by` ,
                    `demo_search_vw` . `is_deleted` AS `is_deleted` ,
                    `demo_search_vw` . `created_at` AS `created_at` ,
                    `demo_search_vw` . `updated_at` AS `updated_at` ,
                    `demo_search_vw` . `demo_owner` AS `demo_owner` ,
                    `demo_search_vw` . `entity_type` AS `entity_type` ,
                    `demo_search_vw` . `usage_count` AS `usage_count` ,
                    `demo_search_vw` . `is_archived` AS `is_archived` ,
                    `demo_search_vw` . `description` AS `description` ,
                    `demo_search_vw` . `is_published` AS `is_published` ,
                    `demo_search_vw` . `current_rank` AS `current_rank` ,
                    `demo_search_vw` . `demo_language` AS `demo_language` ,
                    `demo_search_vw` . `demo_platform` AS `demo_platform` ,
                    `demo_search_vw` . `external_title` AS `external_title` ,
                    `demo_search_vw` . `demo_owner_name` AS `demo_owner_name` ,
                    `demo_search_vw` . `created_by_name` AS `created_by_name` ,
                    `demo_search_vw` . `demoboard_count` AS `demoboard_count` ,
                    `demo_search_vw` . `public_link_count` AS `public_link_count` ,
                    `demo_search_vw` . `visible_in_library` AS `visible_in_library`\s
                FROM
                    `demo_search_vw`\s
                WHERE
                    `demo_search_vw` . `org_id` = ?\s
                    AND `demo_search_vw` . `uuid_type` IN (...)\s
                    AND (
                        (
                            `demo_search_vw` . `is_deleted` = ?\s
                            AND `demo_search_vw` . `entity_type` = ?\s
                        )\s
                        AND (
                            (
                                `UPPER` (
                                    `demo_search_vw` . `name`\s
                                ) LIKE `UPPER` (
                                    ?
                                )\s
                                AND `UPPER` (
                                    `demo_search_vw` . `name`\s
                                ) LIKE `UPPER` (
                                    ?
                                )\s
                                AND `UPPER` (
                                    `demo_search_vw` . `name`\s
                                ) LIKE `UPPER` (
                                    ?
                                )\s
                                AND `UPPER` (
                                    `demo_search_vw` . `name`\s
                                ) LIKE `UPPER` (
                                    ?
                                )\s
                                AND `UPPER` (
                                    `demo_search_vw` . `name`\s
                                ) LIKE `UPPER` (
                                    ?
                                )\s
                                AND `UPPER` (
                                    `demo_search_vw` . `name`\s
                                ) LIKE `UPPER` (
                                    ?
                                )\s
                                AND `UPPER` (
                                    `demo_search_vw` . `name`\s
                                ) LIKE `UPPER` (
                                    ?
                                )\s
                                AND `UPPER` (
                                    `demo_search_vw` . `name`\s
                                ) LIKE `UPPER` (
                                    ?
                                )\s
                                AND `UPPER` (
                                    `demo_search_vw` . `name`\s
                                ) LIKE `UPPER` (
                                    ?
                                )\s
                                AND `UPPER` (
                                    `demo_search_vw` . `name`\s
                                ) LIKE `UPPER` (
                                    ?
                                )\s
                                OR (
                                    `UPPER` (
                                        `demo_search_vw` . `description`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                )\s
                                OR (
                                    `UPPER` (
                                        `demo_search_vw` . `demo_owner_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `demo_search_vw` . `demo_owner_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `demo_search_vw` . `demo_owner_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `demo_search_vw` . `demo_owner_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `demo_search_vw` . `demo_owner_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `demo_search_vw` . `demo_owner_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `demo_search_vw` . `demo_owner_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `demo_search_vw` . `demo_owner_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `demo_search_vw` . `demo_owner_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `demo_search_vw` . `demo_owner_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                )\s
                                OR (
                                    `UPPER` (
                                        `demo_search_vw` . `created_by_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `demo_search_vw` . `created_by_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `demo_search_vw` . `created_by_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `demo_search_vw` . `created_by_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `demo_search_vw` . `created_by_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `demo_search_vw` . `created_by_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `demo_search_vw` . `created_by_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `demo_search_vw` . `created_by_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `demo_search_vw` . `created_by_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                    AND `UPPER` (
                                        `demo_search_vw` . `created_by_name`\s
                                    ) LIKE `UPPER` (
                                        ?
                                    )\s
                                )\s
                            )\s
                            AND (
                                `demo_search_vw` . `created_by` IN (...)\s
                            )\s
                            AND (
                                `demo_search_vw` . `created_at` BETWEEN ? AND ?\s
                            )\s
                            AND (
                                `demo_search_vw` . `is_archived` = ?\s
                            )\s
                            AND (
                                `demo_search_vw` . `is_valid` = ?\s
                            )\s
                            AND (
                                `demo_search_vw` . `demo_type` IN (...)\s
                            )\s
                            AND (
                                `demo_search_vw` . `demo_platform` IN (...)\s
                            )\s
                            AND (
                                `demo_search_vw` . `demo_language` IN (...)\s
                                OR `demo_search_vw` . `demo_language` IS NULL\s
                            )\s
                            AND (
                                `demo_search_vw` . `demo_owner` IN (...)\s
                            )\s
                            AND (
                                `demo_search_vw` . `is_published` = ?\s
                                OR `demo_search_vw` . `is_published` IS NULL\s
                            )\s
                        )\s
                    )\s
                ORDER BY
                    `demo_search_vw` . `current_rank` DESC
            """;

        System.out.println(reformatQuery(sql));
    }

}