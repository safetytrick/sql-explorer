package com.github.safetytrick.sqlexplorer.analysis.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

}
