package com.github.safetytrick.sqlexplorer.cleanup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SqlCleanerTest {

    @Test
    void remove_dangling_where() {
        String sql = """
                SELECT
                    `customer_membership` . `id` ,
                    `customer_membership` . `customer_id` AS `customerId` ,
                    `customer_membership` . `store_id` AS `storeId` ,
                    `customer_membership` . `group_id` AS `groupId` ,
                    `customer_membership` . `group_name` AS `groupName` ,
                    `customer_membership` . `last_seen` AS `lastSeen` ,
                    `customer_membership` . `line_of_business` AS `lineOfBusiness` ,
                    `customer_membership` . `account_number` AS `accountNumber` ,
                    `customer_membership` . `status` ,
                    `customer_membership` . `policy_number_suffix` AS `policyNumberSuffix` ,
                    `customer_membership` . `service_start` AS `serviceStart` ,
                    `customer_membership` . `service_end` AS `serviceEnd` ,
                    `customer_membership` . `provider_id` AS `providerId` ,
                    `customer_membership` . `provider` ,
                    `customer_membership` . `remote_identifier` AS `remoteIdentifier` ,
                    `customer_membership` . `data_source` AS `dataSourceId`
                FROM
                    `customer_visit_membership`
                JOIN
                    `customer_membership`
                        ON `customer_visit_membership` . `customer_membership_id` = `customer_membership` . `id`
                WHERE
                    \
            """;

        assertFalse(new SqlCleaner().cleanSql(sql).getSql().contains("WHERE"));
    }

    @Test
    void collapse_in_question_mark() {
        String sql = """
                SELECT
                    *
                FROM
                    `store_identifier`
                WHERE
                    `identifier` IN (
                        ? ? ? ? ? ? ? ? ? ? ? ? ? ? ?
                    ) \
            """;

        SqlCleaner.CleanedSql cleanedSql = new SqlCleaner().cleanSql(sql);
        assertFalse(cleanedSql.getSql().matches(".*\\?\\s+\\?"));
        assertTrue(cleanedSql.isModified());
        assertFalse(cleanedSql.isTruncated());
    }

    @Test
    void collapse_if_statements() {
        String sql = "SELECT `pal` . `accessed_at` AS `viewed_store_id` , `f` . `type` AS ? , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... , IF ( `f` . `type` = ?, ... ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) ) AS ? , `f` . `state` AS ? , `f` . `zip` AS ? , `DATE_FORMAT` ( `pal` . `accessed_on` , ? ) AS ? , COUNT ( DISTINCTROW `pal` . `id` ) ? , COUNT ( DISTINCTROW `pal`";

        SqlCleaner.CleanedSql cleanedSql = new SqlCleaner().cleanSql(sql);

        assertTrue(cleanedSql.isModified());
        assertEquals(cleanedSql.getSql().indexOf("IF ( `f` . `type` = ?"),
                cleanedSql.getSql().lastIndexOf("IF ( `f` . `type` = ?"),
                "only one instance should be left");
    }

}
