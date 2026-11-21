package com.github.safetytrick.sqlexplorer.analysis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.stream.Collectors;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static com.github.safetytrick.sqlexplorer.analysis.Assertions.assertContainsColumnReferences;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SqlAnalysisServiceTest {

    SchemaFixture fixture = SchemaFixture.allFixtures();

    SqlAnalysisService sqlAnalysisService = fixture.service(
        new TableNameAnalyzer(),
        new SchemaAnalyzer(),
        new JoinColumnAnalyzer(fixture.resolver()),
        new WhereClauseAnalyzer(fixture.resolver()),
        new GeneratedSqlAnalyzer());

    @Test
    void schemaFromSql() throws JSQLParserException {

        String sql = """
            SELECT
                    `performance_schema` . `events_waits_summary_global_by_event_name` . `EVENT_NAME` AS `events` ,
                    `performance_schema` . `events_waits_summary_global_by_event_name` . `COUNT_STAR` AS `total` ,
                    `performance_schema` . `events_waits_summary_global_by_event_name` . `SUM_TIMER_WAIT` AS `total_latency` ,
                    `performance_schema` . `events_waits_summary_global_by_event_name` . `AVG_TIMER_WAIT` AS `avg_latency` ,
                    `performance_schema` . `events_waits_summary_global_by_event_name` . `MAX_TIMER_WAIT` AS `max_latency`
                FROM
                    `performance_schema` . `events_waits_summary_global_by_event_name`
                WHERE
                    (
                        (
                            `performance_schema` . `events_waits_summary_global_by_event_name` . `EVENT_NAME` != ?
                        )
                        AND (
                            `performance_schema` . `events_waits_summary_global_by_event_name` . `SUM_TIMER_WAIT` > ?
                        )
                    )
                ORDER BY
                    `performance_schema` . `events_waits_summary_global_by_event_name` . `SUM_TIMER_WAIT` DESC
            """;

        SqlProfile.Builder builder = SqlProfile.newBuilder();
        builder.setQuery(sql);

        sqlAnalysisService.analyzeSql(sql, builder);

        builder.complete("some_other_schema");

        assertEquals("performance_schema", builder.getSchemaName());
        assertThat(builder.getTables())
            .hasSize(1)
            .anyMatch(table -> table.equalsNormalized("performance_schema.events_waits_summary_global_by_event_name"));
    }

    @Test
    void insertValues() throws JSQLParserException {
        String sql = """
            INSERT INTO
                    `some_table` (
                        `created_on`, `id`)
                VALUES
                    (...)
            """;

        SqlProfile.Builder builder = SqlProfile.newBuilder();
        sqlAnalysisService.analyzeSql(sql, builder);

        assertFalse(builder.getAnalysisFailed());

    }

    @Disabled("lib fails to parse BINARY")
    @Test
    void pg_parse_failure() throws JSQLParserException {
        String sql = """
                SELECT
                    `id` ,
                    `branch_id` AS `branchId` ,
                    NAME ,
                    `invisible` ,
                    `mutual_exclusion_identifier` AS `mutualExclusionIdentifier` ,
                    `group_label` AS `groupLabel` ,
                    `group_identifier` AS `groupIdentifier` ,
                    `group_family_identifier` AS `groupFamilyIdentifier` ,
                    `show_on_list` AS `showOnList` ,
                    `show_on_local_alert` AS `showOnLocalAlert` ,
                    `show_on_remote_alert` AS `showOnRemoteAlert` ,
                    `description` ,
                    `mutual_exclusion_identifier` AS `mutualExclusionIdentifier` ,
                    `color` ,
                    `created_on` AS `createdOn` ,
                    `created_by` AS `createdById` ,
                    `deleted_by` AS `deletedById` ,
                    `deleted_on` AS `deletedOn` ,
                    `org_id` AS `orgId` ,
                    `hide_on_groups_page` AS `hideOnGroupsPage`
                FROM
                    `member_group`
                WHERE
                    `branch_id` = ?
                    AND BINARY `group_identifier` = ?
                    AND `deleted_on` IS NULL
                    AND `deleted_by` IS NULL
            """;

        SqlProfile.Builder builder = SqlProfile.newBuilder();
        sqlAnalysisService.analyzeSql(sql, builder);

        assertFalse(builder.getAnalysisFailed());
    }

    @Test
    void pr_parse_failure() throws JSQLParserException {
        String sql = """
                SELECT
                    `pr` . `id` ,
                    `pr` . `member_id` AS `memberId` ,
                    `pr` . `managing_branch_id` AS `managingBranchId` ,
                    `pr` . `managing_branch_id` AS ? ,
                    `pr` . `managing_branch_status` AS `managingBranchStatusCode` ,
                    `pr` . `referring_branch_id` AS `referringBranchId` ,
                    `pr` . `referring_branch_id` AS ? ,
                    `pr` . `referring_branch_status` AS `referringBranchStatusCode` ,
                    `pr` . `type`
                FROM
                    `member_referral` `pr`
                WHERE
                    `pr` . `member_id` = ?
                    AND `pr` . `referring_branch_id` = ?
                    AND `pr` . `type` = ?
            """;

        SqlProfile.Builder builder = SqlProfile.newBuilder();
        sqlAnalysisService.analyzeSql(sql, builder);

        assertFalse(builder.getAnalysisFailed());
    }

    @Test
    void trailing_punctuation() throws JSQLParserException {
        String sql = """
            SELECT
                    `branch0_` . `id` AS `id1_35_0_` ,
                    `branch0_` . `name` AS `name2_35_0_` ,
                    `branch0_` . `address` AS `address3_35_0_` ,
                    `branch0_` . `phone` AS `phone4_35_0_` ,
                    `branch0_` . `contact_name` AS `contact_5_35_0_` ,
                    `branch0_` . `email` AS `email6_35_0_` ,
                    `branch0_` . `participating` AS `particip7_35_0_` ,
                    `branch0_` . `join_date` AS `join_dat8_35_0_` ,
                    `branch0_` . `city` AS `city9_35_0_` ,
                    `branch0_` . `zip` AS `zip10_35_0_` ,
                    `branch0_` . `county` AS `county11_35_0_` ,
                    `branch0_` . `state` AS `state12_35_0_` ,
                    `branch0_` . `latitude` AS `latitud13_35_0_` ,
                    `branch0_` . `longitude` AS `longitu14_35_0_` ,
                    `branch0_` . `url` AS `url15_35_0_` ,
                    `branch0_` . `type` AS `type16_35_0_` ,
                    `branch0_` . `created_on` AS `created17_35_0_` ,
                    `branch0_` . `last_update` AS `last_up18_35_0_` ,
                    `branch0_` . `created_by` AS `created19_35_0_` ,
                    `branch0_` . `parent_branch_id` AS `parent_20_35_0_`
                FROM
                    `branch` `branch0_`
                WHERE
                    `branch0_` .""";

        SqlProfile.Builder builder = SqlProfile.newBuilder();
        sqlAnalysisService.analyzeSql(sql, builder);

        assertFalse(builder.getAnalysisFailed());
    }

    @Test
    void parsing_nested_if() throws JSQLParserException {
        String sql = """
                SELECT
                    `ANY_VALUE` ( `pn` . `first_name` ) AS ? ,
                    `ANY_VALUE` ( `pn` . `last_name` ) AS ? ,
                    `ANY_VALUE` ( IF ( `pid` . `date_of_signup` IS NOT NULL ,
                    `FLOOR` ( TIMESTAMPDIFF ( YEAR ,
                    `pid` . `date_of_signup` ,
                    `pv` . `checkin_date` ) ) ,
                    ? ) ) AS ? ,
                    `ANY_VALUE` ( `ref` . `loyalty_card_number` ) AS `REF` ,
                    `CONCAT` ( ? ,
                    `pv` . `member_id` ) AS ? ,
                    DATE ( `pv` . `checkin_date` ) AS ? ,
                    TIME ( `pv` . `checkin_date` ) AS ? ,
                    `pv` . `account_number` AS ? ,
                    IF ( `pv` . `checkout_disposition` IS NOT NULL ,
                    `pv` . `checkout_disposition` ,
                    ? ) AS ? ,
                    COALESCE ( ( SELECT
                        COUNT ( DISTINCTROW `content_id` )
                    FROM
                        `member_care_guideline`
                    WHERE
                        `member_id` = `pv` . `member_id`
                        AND `content_action` = ? ) ,
                    ? ) AS ? ,
                    `ANY_VALUE` ( IF ( `cp1` . `name` IS NOT NULL ,
                    `cp1` . `name` ,
                    IF ( `cp2` . `name` IS NOT NULL ,
                    `cp2` . `name` ,
                    IF ( `cp3` . `name` IS NOT NULL ,
                    `cp3` . `name` ,
                    ? ) ) ) ) AS ? ,
                    `ANY_VALUE` ( IF ( `cp1` . `phone` IS NOT NULL ,
                    `cp1` . `phone` ,
                    IF ( `cp2` . `phone` IS NOT NULL ,
                    `cp2` . `phone` ,
                    IF ( `cp3` . `phone` IS NOT NULL ,
                    `cp3` . `phone` ,
                    ? ) ) ) ) AS ? ,
            """;

        SqlProfile.Builder builder = SqlProfile.newBuilder();
        sqlAnalysisService.analyzeSql(sql, builder);

        assertFalse(builder.getAnalysisFailed());
    }

    @Disabled("jsql parser failure")
    @Test
    void parsing_if() throws JSQLParserException {
        String sql = """
            SELECT
                    DISTINCTROW IF ( `pident` . `date_of_signup` IS NOT NULL ,
                    `pident` . `date_of_signup` ,
                    ? ) AS ? ,
                    IF ( `pdesc` . `gender` = ?,
                    ... ,
                    IF ( `pdesc` . `gender` = ?,
                    ... ) ) AS ? ,
                    `f` . `name` ? ,
                    DATE ( `pv` . `checkin_date` ) AS ? ,
                    TIME ( `pv` . `checkin_date` ) AS ? ,
                    IF ( `pv` . `main_class` = ?
                    OR ( `pv0` . `id` IS NOT NULL
                    AND `pv` . `session_type` = ? ) ,
                    ?,
                    ... ) AS ? ,
                    DATE ( `pv` . `checkout_date` ) AS ? ,
                    TIME ( `pv` . `checkout_date` ) AS ? ,
                    `pv` . `checkout_disposition` AS ? ,
                    IF ( ( `pv` . `dx_descriptions` LIKE ?
                    OR `pv` . `dx_descriptions` LIKE ? ) ,
                    ?,
                    ... ) AS ? ,
                    IF ( `pv` . `dx_descriptions` LIKE ?,
                    ... ) AS ? ,
                    IF ( `pv` . `dx_descriptions` LIKE ?,
                    ... ) AS ? ,
                    IF ( `pv` . `dx_descriptions` LIKE ?,
                    ... ) AS ? ,
                    IF ( EXISTS ( SELECT
                        *
                    FROM
                        `member_feedback` `pd`
                    JOIN
                        `dx_code` `dx0`
                            ON `pd` . `code` = `dx0` . `id`
                    WHERE
                        `pd` . `member_session_id` = `pv` . `id`
                        AND `dx0` . `code` IN (...) ) ,
                    ?,
                    ... ) AS ? ,
                    IF ( `pv` . `dx_descriptions` NOT LIKE ?
                    AND `pv` . `dx_descriptions` NOT LIKE ?
                    AND `pv` . `dx_descriptions` NOT LIKE ?
                    AND `pv` . `dx_descriptions` NOT LIKE ?
                    AND `pv` . `dx_descriptions` NOT LIKE""";

        SqlProfile.Builder builder = SqlProfile.newBuilder();
        sqlAnalysisService.analyzeSql(sql, builder);

        assertFalse(builder.getAnalysisFailed());
    }

    @Disabled("I think we could work around this")
    @Test
    void trailing_and_with_unclosed_parens() throws JSQLParserException {
        String sql = """
                SELECT
                    `pv0` . *
                FROM
                    ( SELECT
                        `pv1` . *
                    FROM
                        `member_session` `pv1`
                    WHERE
                        `pv1` . `session_type` IN (
                            ?
                        )
                        AND `pv1` . `branch_id` IN (
                            ...
                        )
                        AND `pv1` . `checkin_date` BETWEEN ? AND ?
                        AND (
                            `pv1` . `checkout_disposition` = ?
                            OR (
                                `pv1` . `checkout_disposition` IS NULL
                                AND NOT EXISTS (
                                    SELECT
                                        `pv` . `id`
                                    FROM
                                        `member_session` `pv`
                                    WHERE
                                        `pv` . `member_id` = `pv1` . `member_id`
                                        AND `pv` . `checkin_date` BETWEEN `pv1` . `checkin_date` AND `pv1` . `checkin_date` + INTERVAL ? SQL_TSI_HOUR
                                        AND `pv` . `main_class` = ?
                                )
                            )
                        )
                        AND NOT EXISTS (
                            SELECT
                                DISTINCTROW `cg1` . `content_id`
                            FROM
                                `member_care_guideline` `cg1`
                            WHERE
                                `cg1` . `member_id` = `pv1` . `member_id`
                                AND `cg1` . `created_on` < `pv1` . `checkin_date`
                                AND `cg1` . `category` = ?
                                AND `cg1` . `content_action` IN (
                                    ...
                                )
                                AND NOT EXISTS (
                                    SELECT
                                        `cg2` . `content_action`
                                    FROM
                                        `member_care_guideline` `cg2`
                                    WHERE
                                        `cg2` . `content_id` = `cg1` . `content_id`
                                        AND `cg2` . `created_on` > `cg1` . `created_on`
                                        AND `cg2` . `created_on` < `pv1` . `checkin_date`
                                )
                            )
                            AND NOT EXISTS (
                                SELECT
                                    DISTINCTROW `ch1` . `content_id`
                                FROM
                                    `member_care_history` `ch1`
                                WHERE
                                    `ch1` . `member_id` = `pv1` . `member_id`
                                    AND
            """;

        SqlProfile.Builder builder = SqlProfile.newBuilder();
        sqlAnalysisService.analyzeSql(sql, builder);

        assertFalse(builder.getAnalysisFailed());
    }

    @Test
    void attempt_complete_equal() throws JSQLParserException {
        String sql = """
                SELECT
                    `membervi0_` . `id` AS `id1_137_` ,
                    `membervi0_` . `checkin_date` AS `checkin_da2_137_` ,
                    `membervi0_` . `checkout_date` AS `checkout3_137_` ,
                    `membervi0_` . `account_number` AS `account_4_137_` ,
                    `membervi0_` . `billing_account_number` AS `billing_5_137_` ,
                    `membervi0_` . `created_on` AS `created_6_137_` ,
                    `membervi0_` . `checkout_disposition` AS `checkout7_137_` ,
                    `membervi0_` . `session_type` AS `session_ty8_137_` ,
                    `membervi0_` . `transfer_date` AS `transfer9_137_` ,
                    `membervi0_` . `main_class` AS `main_c10_137_` ,
                    `membervi0_` . `special_categories` AS `special11_137_` ,
                    `membervi0_` . `data_source` AS `data_so12_137_` ,
                    `membervi0_` . `member_id` AS `member13_137_` ,
                    `membervi0_` . `branch_id` AS `branch14_137_`
                FROM
                    `member_session` `membervi0_` CROSS
                JOIN
                    `member_plan` `memberins1_` CROSS
                JOIN
                    `member_feedback` `memberdia2_`
                WHERE
                    `membervi0_` . `member_id` = `memberins1_` . `member_id`
                    AND `memberdia2_` . `member_session_id` = \s
            """;

        SqlProfile.Builder builder = SqlProfile.newBuilder();
        sqlAnalysisService.analyzeSql(sql, builder);

        assertFalse(builder.getAnalysisFailed());
        assertTrue(builder.getTruncated());
    }

    @Test
    void attempt_complete_open_and() throws JSQLParserException {
        String sql = """
                SELECT
                    `membermem0_` . `id` AS `id1_121_` ,
                    `membermem0_` . `created_on` AS `created_2_121_` ,
                    `membermem0_` . `service_start` AS `service_3_121_` ,
                    `membermem0_` . `service_end` AS `service_4_121_` ,
                    `membermem0_` . `last_seen` AS `last_see5_121_` ,
                    `membermem0_` . `line_of_business` AS `line_of_6_121_` ,
                    `membermem0_` . `provider` AS `provider7_121_` ,
                    `membermem0_` . `account_number` AS `account_8_121_` ,
                    `membermem0_` . `provider_id` AS `provider9_121_` ,
                    `membermem0_` . `status` AS `status10_121_` ,
                    `membermem0_` . `remote_identifier` AS `remote_11_121_` ,
                    `membermem0_` . `data_source` AS `data_so12_121_` ,
                    `membermem0_` . `branch_id` AS `branch13_121_` ,
                    `membermem0_` . `member_id` AS `member14_121_` ,
                    `membermem0_` . `group_id` AS `group_i15_121_` ,
                    `membermem0_` . `group_name` AS `group_n16_121_` ,
                    `membermem0_` . `policy_number_suffix` AS `policy_17_121_`
                FROM
                    `member_plan` `membermem0_`
                WHERE
                    `membermem0_` . `member_id` = ?
                    AND (
            """;

        SqlProfile.Builder builder = SqlProfile.newBuilder();
        sqlAnalysisService.analyzeSql(sql, builder);

        assertFalse(builder.getAnalysisFailed());
    }

    @Test
    void odd_failure() throws JSQLParserException {
        String sql = """
                SELECT
                    `membermem0_` . `id` AS `id1_127_` ,
                    `membermem0_` . `created_on` AS `created_2_127_` ,
                    `membermem0_` . `service_start` AS `service_3_127_` ,
                    `membermem0_` . `service_end` AS `service_4_127_` ,
                    `membermem0_` . `last_seen` AS `last_see5_127_` ,
                    `membermem0_` . `line_of_business` AS `line_of_6_127_` ,
                    `membermem0_` . `provider` AS `provider7_127_` ,
                    `membermem0_` . `account_number` AS `account_8_127_` ,
                    `membermem0_` . `provider_id` AS `provider9_127_` ,
                    `membermem0_` . `status` AS `status10_127_` ,
                    `membermem0_` . `remote_identifier` AS `remote_11_127_` ,
                    `membermem0_` . `data_source` AS `data_so12_127_` ,
                    `membermem0_` . `branch_id` AS `branch13_127_` ,
                    `membermem0_` . `member_id` AS `member14_127_` ,
                    `membermem0_` . `group_id` AS `group_i15_127_` ,
                    `membermem0_` . `group_name` AS `group_n16_127_` ,
                    `membermem0_` . `policy_number_suffix` AS `policy_17_127_`
                FROM
                    `member_plan` `membermem0_`
                WHERE
                    `membermem0_` . `member_id` = ?
                    AND (
            """;

        SqlProfile.Builder builder = SqlProfile.newBuilder();
        sqlAnalysisService.analyzeSql(sql, builder);

        assertFalse(builder.getAnalysisFailed());
    }

    @Test
    void alias_question_mark() throws JSQLParserException {
        String sql = """
                SELECT
                    `pv` . *
                FROM
                    `member_session` `pv` ,
                    `member_session_details` `pvd` ,
                    `member_plan` `pm`
                WHERE
                    `pv` . `member_id` = `pm` . `member_id`
                    AND `pvd` . `id` = `pv` . `id`
                    AND `pv` . `checkin_date` >= ?
                    AND `pv` . `checkin_date` <= ?
                    AND (
                        NOW ( ) <= `pm` . `service_end`
                        OR `pm` . `service_end` IS NULL
                    )
                    AND (
                        NOW ( ) >= `pm` . `service_start`
                        OR `pm` . `service_start` IS NULL
                    )
                    AND (
                        `pv` . `session_type` IN (
                            ?
                        )
                        OR `pv` . `main_class` IN (
                            (
                                _charset
                            ) ? , (
                                _charset
                            ) ?
                        )
                    )
                    AND `pm` . `branch_id` = ?
                GROUP BY
                    `pv` . `id`
                ORDER BY
                    `pv` . `checkin_date` DESC
            """;

        SqlProfile.Builder builder = SqlProfile.newBuilder();
        sqlAnalysisService.analyzeSql(sql, builder);

        assertFalse(builder.getAnalysisFailed());
    }

    @Test
    void cast_as_character() throws JSQLParserException {
        String sql = """
                SELECT
                    CAST ( `id` AS CHARACTER (?) )
                FROM
                    `member_referral`
                WHERE
                    `member_id` IN (
                        ...
                    )
            """;

        SqlProfile.Builder builder = SqlProfile.newBuilder();
        sqlAnalysisService.analyzeSql(sql, builder);

        assertFalse(builder.getAnalysisFailed());
    }

    @Test
    void missing_schema_simple_subquery_in_where() throws JSQLParserException {
        String sql = """
                SELECT
                    *
                FROM
                    `dl_activity`
                WHERE
                    `id` IN (
                        SELECT
                            MAX ( `id` )
                        FROM
                            `dl_activity`
                        WHERE
                            STATUS = ?
                            AND `next_stage` = ?
                        GROUP BY
                            `data_load_id` ,
                            FILE
                        ORDER BY
                            `data_load_id` ,
                            FILE
                    ) LIMIT ?, ...
            """;

        SqlProfile.Builder builder = SqlProfile.newBuilder();
        sqlAnalysisService.analyzeSql(sql, builder);

        assertFalse(builder.getAnalysisFailed());
    }

    @Test
    void scope_in_join() throws JSQLParserException {
        String sql = """
                SELECT
                    `tcl` . `id` ,
                    `tcl` . `org_id` AS `orgId` ,
                    `tcl` . `change_by` AS `changedById` ,
                    `tcl` . `created_on` AS `createdOn` ,
                    `tcl` . `approved_by` AS `approvedBy` ,
                    `tcl` . `communication_method` AS `communicationMethod` ,
                    `tcl` . `type` ,
                    ( SELECT
                        `concat` ( `last_name` ,
                        ? ,
                        `first_name` )
                    FROM
                        ORG
                    WHERE
                        `id` = `tcl` . `change_by` ) AS `changedBy`
                FROM
                    `org_change_log` `tcl`
                JOIN
                    (
                        SELECT
                            `org_id` ,
                            MAX ( `created_on` ) `latest_created_on`
                        FROM
                            `org_change_log`
                        WHERE
                            `org_id` IN (
                                ...
                            )
                        GROUP BY
                            `org_id`
                    ) `m_tcl`
                        ON `tcl` . `org_id` = `m_tcl` . `org_id`
                        AND `m_tcl` . `latest_created_on` = `tcl` . `created_on`
            """;

        SqlProfile.Builder builder = SqlProfile.newBuilder();

        sqlAnalysisService.analyzeSql(sql, builder);

        assertFalse(builder.getAnalysisFailed(), builder.getAnalysisFailureReason());
    }

    @Test
    void date_math() throws JSQLParserException {
        String sql = """
                SELECT
                    `id` ,
                    `username` ,
                    `last_login` AS `lastLogin` ,
                    `first_name` AS `firstName` ,
                    `last_name` AS `lastName` ,
                    `email` ,
                    `last_branch_id` AS `lastBranchId`
                FROM
                    ORG `c`
                WHERE
                    NOT EXISTS (
                        SELECT
                            ?
                        FROM
                            `branch_config` `fc`
                        WHERE
                            `fc` . `name` = ?
                            AND `fc` . `value` = ?
                            AND `fc` . `deleted_on` IS NULL
                            AND `fc` . `branch_id` = `c` . `last_branch_id`
                    )
                    AND `c` . `is_blocked` = ?
                    AND `c` . `is_deleted` = ?
                    AND `c` . `last_login` BETWEEN DATE_SUB ( NOW ( ) , INTERVAL ? + ? SQL_TSI_DAY ) AND DATE_SUB ( NOW ( ) , INTERVAL ? SQL_TSI_DAY )
            """;

        SqlProfile.Builder builder = SqlProfile.newBuilder();
        sqlAnalysisService.analyzeSql(sql, builder);

        assertFalse(builder.getAnalysisFailed());
    }

    @Disabled("no idea what the = ? IN statement here means")
    @Test
    void equals_question_in() throws JSQLParserException {
        String sql = """
                SELECT
                    `CONCAT` ( REPLACE ( `rcf` . `value` ,
                    ?,
                    ... ) ,
                    `DATE_FORMAT` ( `re` . `executed_on` ,
                    ? ) ,
                    ? ) AS `filename` ,
                    `re` . `id` ,
                    `re` . `data`
                FROM
                    `report_execution` `re`
                JOIN
                    `report_config_field` `rcf`
                        ON `re` . `report_id` = `rcf` . `report_id`
                JOIN
                    `report` `r`
                        ON `r` . `id` = `re` . `report_id`
                WHERE
                    `re` . `executed_on` >= NOW ( ) - INTERVAL ? SQL_TSI_DAY
                    AND `r` . `branch_id` = ?
                    AND `re` . `status` = ?
                    AND `rcf` . `report_config_field_type` = ? IN (
                        `rcf` . `value` LIKE ?
                        OR `rcf` . `value` LIKE ?
                        OR `rcf` . `value` LIKE ?
                        OR `rcf` . `value` LIKE ?
                        OR `rcf` . `value` LIKE ?
                        OR `rcf` . `value` LIKE ?
                        OR `rcf` . `value` LIKE ?
                        OR `rcf` . `value` LIKE ?
                    )
                    AND `rcf` . `deleted_on` IS NULL
                    AND `re` . `transferred_on` IS NULL
            """;

        SqlProfile.Builder builder = SqlProfile.newBuilder();
        sqlAnalysisService.analyzeSql(sql, builder);

        assertFalse(builder.getAnalysisFailed());
    }

    @Test
    void binary_cast() throws JSQLParserException {
        String sql = """
                SELECT
                    BINARY ( `state` ) ,
                    COUNT ( BINARY ( `state` ) )
                FROM
                    `tdb` . `branch`
                GROUP BY
                    BINARY ( `state` )
                ORDER BY
                    `state`
            """;

        SqlProfile.Builder builder = SqlProfile.newBuilder();
        sqlAnalysisService.analyzeSql(sql, builder);

        assertFalse(builder.getAnalysisFailed());
    }

    @Test
    void qualified_subquery() throws JSQLParserException {
        String sql = """
                SELECT
                    `pv` . `branch_id` ,
                    `DATE_FORMAT` ( `pd` . `created_on` ,
                    ? ) AS ? ,
                    COUNT ( DISTINCTROW `pv` . `member_id` ) AS ?
                FROM
                    `member_feedback` `pd`
                JOIN
                    `member_session` `pv`
                        ON `pd` . `member_session_id` = `pv` . `id`
                WHERE
                    CODE IN (
                        ...
                    )
                    AND `pv` . `branch_id` IN (
                        ...
                    )
                    AND `pd` . `created_on` >= ?
                    AND `pd` . `created_on` <= ? + INTERVAL ? SQL_TSI_DAY
                    AND `pd` . `created_on` = (
                        SELECT
                            MIN ( `pd1` . `created_on` )
                        FROM
                            `member_feedback` `pd1`
                        JOIN
                            `member_session` `pv1`
                                ON `pd1` . `member_session_id` = `pv1` . `id`
                        WHERE
                            `pd1` . `code` IN (
                                ...
                            )
                            AND `pd1` . `member_id` = `pd` . `member_id`
                            AND `pv` . `branch_id` = `pv1` . `branch_id`
                    )
                GROUP BY
                    `pv` . `branch_id`
            """;


        SqlProfile.Builder builder = SqlProfile.newBuilder();
        sqlAnalysisService.analyzeSql(sql, builder);

        assertFalse(builder.getAnalysisFailed());
    }

    @Test
    void qualified_for_no_reason() throws JSQLParserException {
        String sql = """

                SELECT
                    `pn` . `first_name` ,
                    `pn` . `last_name` ,
                    `pa` . `street_address` ,
                    `pa` . `city` ,
                    `pa` . `state` ,
                    `pa` . `zip` ,
                    `pi` . `date_of_signup` ,
                    `f` . `name` ,
                    `dcv` . `id` AS `SESSIONID` ,
                    `dcv` . `member_id`
                FROM
                    `tdb` . `member_name` `pn`
                LEFT JOIN
                    `tdb` . `member_address` `pa`
                        ON `pn` . `member_id` = `pa` . `member_id`
                LEFT JOIN
                    `tdb` . `member_ident` `pi`
                        ON `pn` . `member_id` = `pi` . `member_id`
                LEFT JOIN
                    `tdb` . `deleted_member_session` `dcv`
                        ON `pn` . `member_id` = `dcv` . `member_id`
                LEFT JOIN
                    `tdb` . `branch` `f`
                        ON `f` . `id` = `dcv` . `branch_id`
                WHERE
                    `pn` . `first_name` LIKE ?
                    AND `pn` . `last_name` LIKE ?
                    AND `pa` . `street_address` LIKE ?
                    AND `main_class` IN (
                        ...
                    )
                ORDER BY
                    `checkin_date` DESC LIMIT ?
            """;

        SqlProfile.Builder builder = SqlProfile.newBuilder();
        sqlAnalysisService.analyzeSql(sql, builder);

        assertFalse(builder.getAnalysisFailed());
    }

    @Test
    void column_name_as_table() throws JSQLParserException {
        String sql = """

                SELECT
                    `tmp` . `contributor_id` ,
                    COUNT ( DISTINCTROW `pi` . `member_id` ) AS `num_members` ,
                    COUNT ( DISTINCTROW `tmp` . `content_id` ) AS `num_insights` ,
                    COUNT ( DISTINCTROW `pal` . `id` ) AS `member_page_views` ,
                    COUNT ( DISTINCTROW `cn` . `id` ) AS `num_insight_shared_through_alert` ,
                    ( SELECT
                        COUNT ( `id` )
                    FROM
                        `analytics` . `contribution`
                    WHERE
                        `content_id` = `tmp` . `content_id`
                        AND `contributor_id` != `tmp` . `contributor_id`
                        AND `branch_id` = `tmp` . `branch_id` ) AS `updates_at_same_branch`
                FROM
                    ( SELECT
                        `c` . `contributor_id` ,
                        `c` . `content_id` ,
                        MIN ( `c` . `created_on` ) AS `insight_created` ,
                        `c` . `branch_id`
                    FROM
                        `analytics` . `contribution` `c`
                    WHERE
                        `contributor_id` IN (
                            ...
                        )
                    GROUP BY
                        `c` . `content_id` ,
                        `c` . `contributor_id` ) `tmp`
                LEFT JOIN
                    `analytics` . `insight` `i`
                        ON `tmp` . `content_id` = `i` . `content_id`
                LEFT JOIN
                    `member_insight` `pi`
                        ON `i` . `id` = `pi` . `insight_id`
                LEFT JOIN
                    `member_access_log` `pal`
                        ON `pi` . `member_id` = `pal` . `member_id`
                        AND `pal` . `accessed_by` != `tmp` . `contributor_id`
                        AND `pal` .
            """;

        SqlProfile.Builder builder = SqlProfile.newBuilder();
        sqlAnalysisService.analyzeSql(sql, builder);

        assertFalse(builder.getAnalysisFailed());

        assertFalse(builder.getJoinColumns().stream().anyMatch(column -> column.toString().contains("content_id.content_id")));

    }

    @Test
    void multi_schema() throws JSQLParserException {
        String sql = """
               SELECT
                    `tmp` . `contributor_id` ,
                    COUNT ( DISTINCTROW `pi` . `member_id` ) AS `num_members` ,
                    COUNT ( DISTINCTROW `tmp` . `content_id` ) AS `num_insights` ,
                    COUNT ( DISTINCTROW `pal` . `id` ) AS `member_page_views` ,
                    COUNT ( DISTINCTROW `cn` . `id` ) AS `num_insight_shared_through_alert` ,
                    ( SELECT
                        COUNT ( `id` )
                    FROM
                        `analytics` . `contribution`
                    WHERE
                        `content_id` = `tmp` . `content_id`
                        AND `contributor_id` != `tmp` . `contributor_id`
                        AND `branch_id` = `tmp` . `branch_id` ) AS `updates_at_same_branch`
                FROM
                    ( SELECT
                        `c` . `contributor_id` ,
                        `c` . `content_id` ,
                        MIN ( `c` . `created_on` ) AS `insight_created` ,
                        `c` . `branch_id`
                    FROM
                        `analytics` . `contribution` `c`
                    WHERE
                        `contributor_id` IN (
                            ...
                        )
                    GROUP BY
                        `c` . `content_id` ,
                        `c` . `contributor_id` ) `tmp`
                LEFT JOIN
                    `analytics` . `insight` `i`
                        ON `tmp` . `content_id` = `i` . `content_id`
                LEFT JOIN
                    `member_insight` `pi`
                        ON `i` . `id` = `pi` . `insight_id`
                LEFT JOIN
                    `member_access_log` `pal`
                        ON `pi` . `member_id` = `pal` . `member_id`
                        AND `pal` . `accessed_by` != `tmp` . `contributor_id`
                        AND `pal` .
            """;

        SqlProfile.Builder builder = SqlProfile.newBuilder();
        builder.setSchemaName("tdb");
        sqlAnalysisService.analyzeSql(sql, builder);

        assertTrue(builder.getIsMultiSchema());
    }

    @Test
    void parse_slow_resources() throws JSQLParserException {
        for (String slowParsed : new String[]{"1cf", "50e", "419", "b70", "c51", "928"}) {
            String sqlResource = getSqlResource("slow-parse-" + slowParsed + ".sql");
            SqlProfile.Builder builder = SqlProfile.newBuilder();
            sqlAnalysisService.analyzeSql(sqlResource, builder);
        }
    }

    private String getSqlResource(String name) {
        try(InputStream inputStream = getClass().getResourceAsStream(name)) {
            Objects.requireNonNull(inputStream);
            try (var reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                return reader.lines()
                    .filter(line -> !line.startsWith("--"))
                    .collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
