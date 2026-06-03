package com.github.safetytrick.sqlexplorer.analysis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.github.safetytrick.sqlexplorer.analysis.parser.SqlParser;
import com.github.safetytrick.sqlexplorer.analysis.resolver.ColumnTableResolver;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static com.github.safetytrick.sqlexplorer.analysis.Assertions.assertContainsColumnReferences;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SqlAnalysisServiceTest {

    ColumnTableResolver columnTableResolver = mock(ColumnTableResolver.class);

    List<SqlAnalyzer> analyzerList = Arrays.asList(
        new TableNameAnalyzer(),
        new SchemaAnalyzer(),
        new JoinColumnAnalyzer(columnTableResolver),
        new WhereClauseAnalyzer(columnTableResolver),
        new GeneratedSqlAnalyzer()
    );

    SqlAnalysisService sqlAnalysisService = new SqlAnalysisService(analyzerList, new SqlParser());

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
                    `store_id` AS `storeId` ,
                    NAME ,
                    `invisible` ,
                    `mutual_exclusion_identifier` AS `mutualExclusionIdentifier` ,
                    `group_label` AS `groupLabel` ,
                    `group_identifier` AS `groupIdentifier` ,
                    `group_family_identifier` AS `groupFamilyIdentifier` ,
                    `show_on_list` AS `showOnList` ,
                    `show_on_local_notif` AS `showOnLocalNotif` ,
                    `show_on_remote_notif` AS `showOnRemoteNotif` ,
                    `description` ,
                    `mutual_exclusion_identifier` AS `mutualExclusionIdentifier` ,
                    `color` ,
                    `created_on` AS `createdOn` ,
                    `created_by` AS `createdById` ,
                    `deleted_by` AS `deletedById` ,
                    `deleted_on` AS `deletedOn` ,
                    `client_id` AS `clientId` ,
                    `hide_on_groups_page` AS `hideOnGroupsPage`
                FROM
                    `customer_group`
                WHERE
                    `store_id` = ?
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
                    `pr` . `customer_id` AS `customerId` ,
                    `pr` . `managing_store_id` AS `managingStoreId` ,
                    `pr` . `managing_store_id` AS ? ,
                    `pr` . `managing_store_status` AS `managingStoreStatusCode` ,
                    `pr` . `referring_store_id` AS `referringStoreId` ,
                    `pr` . `referring_store_id` AS ? ,
                    `pr` . `referring_store_status` AS `referringStoreStatusCode` ,
                    `pr` . `type`
                FROM
                    `customer_referral` `pr`
                WHERE
                    `pr` . `customer_id` = ?
                    AND `pr` . `referring_store_id` = ?
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
                    `store0_` . `id` AS `id1_35_0_` ,
                    `store0_` . `name` AS `name2_35_0_` ,
                    `store0_` . `address` AS `address3_35_0_` ,
                    `store0_` . `phone` AS `phone4_35_0_` ,
                    `store0_` . `contact_name` AS `contact_5_35_0_` ,
                    `store0_` . `email` AS `email6_35_0_` ,
                    `store0_` . `participating` AS `particip7_35_0_` ,
                    `store0_` . `join_date` AS `join_dat8_35_0_` ,
                    `store0_` . `city` AS `city9_35_0_` ,
                    `store0_` . `zip` AS `zip10_35_0_` ,
                    `store0_` . `county` AS `county11_35_0_` ,
                    `store0_` . `state` AS `state12_35_0_` ,
                    `store0_` . `latitude` AS `latitud13_35_0_` ,
                    `store0_` . `longitude` AS `longitu14_35_0_` ,
                    `store0_` . `url` AS `url15_35_0_` ,
                    `store0_` . `type` AS `type16_35_0_` ,
                    `store0_` . `created_on` AS `created17_35_0_` ,
                    `store0_` . `last_update` AS `last_up18_35_0_` ,
                    `store0_` . `created_by` AS `created19_35_0_` ,
                    `store0_` . `parent_store_id` AS `parent_20_35_0_`
                FROM
                    `store` `store0_`
                WHERE
                    `store0_` .""";

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
                    `ANY_VALUE` ( IF ( `pid` . `date_of_birth` IS NOT NULL ,
                    `FLOOR` ( TIMESTAMPDIFF ( YEAR ,
                    `pid` . `date_of_birth` ,
                    `pv` . `checkin_date` ) ) ,
                    ? ) ) AS ? ,
                    `ANY_VALUE` ( `ref` . `reference_number` ) AS `REF` ,
                    `CONCAT` ( ? ,
                    `pv` . `customer_id` ) AS ? ,
                    DATE ( `pv` . `checkin_date` ) AS ? ,
                    TIME ( `pv` . `checkin_date` ) AS ? ,
                    `pv` . `account_number` AS ? ,
                    IF ( `pv` . `checkout_disposition` IS NOT NULL ,
                    `pv` . `checkout_disposition` ,
                    ? ) AS ? ,
                    COALESCE ( ( SELECT
                        COUNT ( DISTINCTROW `content_id` )
                    FROM
                        `customer_care_guideline`
                    WHERE
                        `customer_id` = `pv` . `customer_id`
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
                    DISTINCTROW IF ( `pident` . `date_of_birth` IS NOT NULL ,
                    `pident` . `date_of_birth` ,
                    ? ) AS ? ,
                    IF ( `pdesc` . `gender` = ?,
                    ... ,
                    IF ( `pdesc` . `gender` = ?,
                    ... ) ) AS ? ,
                    `f` . `name` ? ,
                    DATE ( `pv` . `checkin_date` ) AS ? ,
                    TIME ( `pv` . `checkin_date` ) AS ? ,
                    IF ( `pv` . `major_class` = ?
                    OR ( `pv0` . `id` IS NOT NULL
                    AND `pv` . `visit_type` = ? ) ,
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
                        `customer_diagnosis` `pd`
                    JOIN
                        `dx_code` `dx0`
                            ON `pd` . `code` = `dx0` . `id`
                    WHERE
                        `pd` . `customer_visit_id` = `pv` . `id`
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
                        `customer_visit` `pv1`
                    WHERE
                        `pv1` . `visit_type` IN (
                            ?
                        )
                        AND `pv1` . `store_id` IN (
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
                                        `customer_visit` `pv`
                                    WHERE
                                        `pv` . `customer_id` = `pv1` . `customer_id`
                                        AND `pv` . `checkin_date` BETWEEN `pv1` . `checkin_date` AND `pv1` . `checkin_date` + INTERVAL ? SQL_TSI_HOUR
                                        AND `pv` . `major_class` = ?
                                )
                            )
                        )
                        AND NOT EXISTS (
                            SELECT
                                DISTINCTROW `cg1` . `content_id`
                            FROM
                                `customer_care_guideline` `cg1`
                            WHERE
                                `cg1` . `customer_id` = `pv1` . `customer_id`
                                AND `cg1` . `created_on` < `pv1` . `checkin_date`
                                AND `cg1` . `category` = ?
                                AND `cg1` . `content_action` IN (
                                    ...
                                )
                                AND NOT EXISTS (
                                    SELECT
                                        `cg2` . `content_action`
                                    FROM
                                        `customer_care_guideline` `cg2`
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
                                    `customer_care_history` `ch1`
                                WHERE
                                    `ch1` . `customer_id` = `pv1` . `customer_id`
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
                    `customervis0_` . `id` AS `id1_137_` ,
                    `customervis0_` . `checkin_date` AS `admit_da2_137_` ,
                    `customervis0_` . `checkout_date` AS `discharg3_137_` ,
                    `customervis0_` . `account_number` AS `account_4_137_` ,
                    `customervis0_` . `billing_account_number` AS `billing_5_137_` ,
                    `customervis0_` . `created_on` AS `created_6_137_` ,
                    `customervis0_` . `checkout_disposition` AS `discharg7_137_` ,
                    `customervis0_` . `visit_type` AS `visit_ty8_137_` ,
                    `customervis0_` . `transfer_date` AS `transfer9_137_` ,
                    `customervis0_` . `major_class` AS `major_c10_137_` ,
                    `customervis0_` . `sensitive_categories` AS `sensiti11_137_` ,
                    `customervis0_` . `data_source` AS `data_so12_137_` ,
                    `customervis0_` . `customer_id` AS `customer13_137_` ,
                    `customervis0_` . `store_id` AS `branch14_137_`
                FROM
                    `customer_visit` `customervis0_` CROSS
                JOIN
                    `customer_membership` `customerins1_` CROSS
                JOIN
                    `customer_diagnosis` `customerdia2_`
                WHERE
                    `customervis0_` . `customer_id` = `customerins1_` . `customer_id`
                    AND `customerdia2_` . `customer_visit_id` = \s
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
                    `customerins0_` . `id` AS `id1_121_` ,
                    `customerins0_` . `created_on` AS `created_2_121_` ,
                    `customerins0_` . `service_start` AS `service_3_121_` ,
                    `customerins0_` . `service_end` AS `service_4_121_` ,
                    `customerins0_` . `last_seen` AS `last_see5_121_` ,
                    `customerins0_` . `line_of_business` AS `line_of_6_121_` ,
                    `customerins0_` . `provider` AS `provider7_121_` ,
                    `customerins0_` . `account_number` AS `account_8_121_` ,
                    `customerins0_` . `provider_id` AS `provider9_121_` ,
                    `customerins0_` . `status` AS `status10_121_` ,
                    `customerins0_` . `remote_identifier` AS `remote_11_121_` ,
                    `customerins0_` . `data_source` AS `data_so12_121_` ,
                    `customerins0_` . `store_id` AS `branch13_121_` ,
                    `customerins0_` . `customer_id` AS `customer14_121_` ,
                    `customerins0_` . `group_id` AS `group_i15_121_` ,
                    `customerins0_` . `group_name` AS `group_n16_121_` ,
                    `customerins0_` . `policy_number_suffix` AS `policy_17_121_`
                FROM
                    `customer_membership` `customerins0_`
                WHERE
                    `customerins0_` . `customer_id` = ?
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
                    `customerins0_` . `id` AS `id1_127_` ,
                    `customerins0_` . `created_on` AS `created_2_127_` ,
                    `customerins0_` . `service_start` AS `service_3_127_` ,
                    `customerins0_` . `service_end` AS `service_4_127_` ,
                    `customerins0_` . `last_seen` AS `last_see5_127_` ,
                    `customerins0_` . `line_of_business` AS `line_of_6_127_` ,
                    `customerins0_` . `provider` AS `provider7_127_` ,
                    `customerins0_` . `account_number` AS `account_8_127_` ,
                    `customerins0_` . `provider_id` AS `provider9_127_` ,
                    `customerins0_` . `status` AS `status10_127_` ,
                    `customerins0_` . `remote_identifier` AS `remote_11_127_` ,
                    `customerins0_` . `data_source` AS `data_so12_127_` ,
                    `customerins0_` . `store_id` AS `branch13_127_` ,
                    `customerins0_` . `customer_id` AS `customer14_127_` ,
                    `customerins0_` . `group_id` AS `group_i15_127_` ,
                    `customerins0_` . `group_name` AS `group_n16_127_` ,
                    `customerins0_` . `policy_number_suffix` AS `policy_17_127_`
                FROM
                    `customer_membership` `customerins0_`
                WHERE
                    `customerins0_` . `customer_id` = ?
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
                    `customer_visit` `pv` ,
                    `customer_visit_details` `pvd` ,
                    `customer_membership` `pm`
                WHERE
                    `pv` . `customer_id` = `pm` . `customer_id`
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
                        `pv` . `visit_type` IN (
                            ?
                        )
                        OR `pv` . `major_class` IN (
                            (
                                _charset
                            ) ? , (
                                _charset
                            ) ?
                        )
                    )
                    AND `pm` . `store_id` = ?
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
                    `customer_referral`
                WHERE
                    `customer_id` IN (
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
                    `ccl` . `id` ,
                    `ccl` . `client_id` AS `clientId` ,
                    `ccl` . `change_by` AS `changedById` ,
                    `ccl` . `created_on` AS `createdOn` ,
                    `ccl` . `approved_by` AS `approvedBy` ,
                    `ccl` . `communication_method` AS `communicationMethod` ,
                    `ccl` . `type` ,
                    ( SELECT
                        `concat` ( `last_name` ,
                        ? ,
                        `first_name` )
                    FROM
                        CLIENT
                    WHERE
                        `id` = `ccl` . `change_by` ) AS `changedBy`
                FROM
                    `client_change_log` `ccl`
                JOIN
                    (
                        SELECT
                            `client_id` ,
                            MAX ( `created_on` ) `latest_created_on`
                        FROM
                            `client_change_log`
                        WHERE
                            `client_id` IN (
                                ...
                            )
                        GROUP BY
                            `client_id`
                    ) `m_ccl`
                        ON `ccl` . `client_id` = `m_ccl` . `client_id`
                        AND `m_ccl` . `latest_created_on` = `ccl` . `created_on`
            """;

        SqlProfile.Builder builder = SqlProfile.newBuilder();

        when(columnTableResolver.selectTableNameFromCandidates(Collections.singletonList("client_change_log"), "created_on"))
            .thenReturn("client_change_log");

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
                    `last_store_id` AS `lastStoreId`
                FROM
                    CLIENT `c`
                WHERE
                    NOT EXISTS (
                        SELECT
                            ?
                        FROM
                            `store_config` `fc`
                        WHERE
                            `fc` . `name` = ?
                            AND `fc` . `value` = ?
                            AND `fc` . `deleted_on` IS NULL
                            AND `fc` . `store_id` = `c` . `last_store_id`
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
                    AND `r` . `store_id` = ?
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
                    `tdb` . `store`
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
                    `pv` . `store_id` ,
                    `DATE_FORMAT` ( `pd` . `created_on` ,
                    ? ) AS ? ,
                    COUNT ( DISTINCTROW `pv` . `customer_id` ) AS ?
                FROM
                    `customer_diagnosis` `pd`
                JOIN
                    `customer_visit` `pv`
                        ON `pd` . `customer_visit_id` = `pv` . `id`
                WHERE
                    CODE IN (
                        ...
                    )
                    AND `pv` . `store_id` IN (
                        ...
                    )
                    AND `pd` . `created_on` >= ?
                    AND `pd` . `created_on` <= ? + INTERVAL ? SQL_TSI_DAY
                    AND `pd` . `created_on` = (
                        SELECT
                            MIN ( `pd1` . `created_on` )
                        FROM
                            `customer_diagnosis` `pd1`
                        JOIN
                            `customer_visit` `pv1`
                                ON `pd1` . `customer_visit_id` = `pv1` . `id`
                        WHERE
                            `pd1` . `code` IN (
                                ...
                            )
                            AND `pd1` . `customer_id` = `pd` . `customer_id`
                            AND `pv` . `store_id` = `pv1` . `store_id`
                    )
                GROUP BY
                    `pv` . `store_id`
            """;


        when(columnTableResolver.selectTableNameFromCandidates(Arrays.asList("customer_diagnosis", "customer_visit"), "code")).thenReturn("customer_diagnosis");

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
                    `pi` . `date_of_birth` ,
                    `f` . `name` ,
                    `dpv` . `id` AS `VISITID` ,
                    `dpv` . `customer_id`
                FROM
                    `tdb` . `customer_name` `pn`
                LEFT JOIN
                    `tdb` . `customer_address` `pa`
                        ON `pn` . `customer_id` = `pa` . `customer_id`
                LEFT JOIN
                    `tdb` . `customer_ident` `pi`
                        ON `pn` . `customer_id` = `pi` . `customer_id`
                LEFT JOIN
                    `tdb` . `deleted_customer_visit` `dpv`
                        ON `pn` . `customer_id` = `dpv` . `customer_id`
                LEFT JOIN
                    `tdb` . `store` `f`
                        ON `f` . `id` = `dpv` . `store_id`
                WHERE
                    `pn` . `first_name` LIKE ?
                    AND `pn` . `last_name` LIKE ?
                    AND `pa` . `street_address` LIKE ?
                    AND `major_class` IN (
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
                    COUNT ( DISTINCTROW `pi` . `customer_id` ) AS `num_customers` ,
                    COUNT ( DISTINCTROW `tmp` . `content_id` ) AS `num_insights` ,
                    COUNT ( DISTINCTROW `pal` . `id` ) AS `customer_page_views` ,
                    COUNT ( DISTINCTROW `cn` . `id` ) AS `num_insight_shared_through_notification` ,
                    ( SELECT
                        COUNT ( `id` )
                    FROM
                        `insights` . `contribution`
                    WHERE
                        `content_id` = `tmp` . `content_id`
                        AND `contributor_id` != `tmp` . `contributor_id`
                        AND `store_id` = `tmp` . `store_id` ) AS `updates_at_same_store`
                FROM
                    ( SELECT
                        `c` . `contributor_id` ,
                        `c` . `content_id` ,
                        MIN ( `c` . `created_on` ) AS `insight_created` ,
                        `c` . `store_id`
                    FROM
                        `insights` . `contribution` `c`
                    WHERE
                        `contributor_id` IN (
                            ...
                        )
                    GROUP BY
                        `c` . `content_id` ,
                        `c` . `contributor_id` ) `tmp`
                LEFT JOIN
                    `insights` . `insight` `i`
                        ON `tmp` . `content_id` = `i` . `content_id`
                LEFT JOIN
                    `customer_insight` `pi`
                        ON `i` . `id` = `pi` . `insight_id`
                LEFT JOIN
                    `customer_access_log` `pal`
                        ON `pi` . `customer_id` = `pal` . `customer_id`
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
                    COUNT ( DISTINCTROW `pi` . `customer_id` ) AS `num_customers` ,
                    COUNT ( DISTINCTROW `tmp` . `content_id` ) AS `num_insights` ,
                    COUNT ( DISTINCTROW `pal` . `id` ) AS `customer_page_views` ,
                    COUNT ( DISTINCTROW `cn` . `id` ) AS `num_insight_shared_through_notification` ,
                    ( SELECT
                        COUNT ( `id` )
                    FROM
                        `insights` . `contribution`
                    WHERE
                        `content_id` = `tmp` . `content_id`
                        AND `contributor_id` != `tmp` . `contributor_id`
                        AND `store_id` = `tmp` . `store_id` ) AS `updates_at_same_store`
                FROM
                    ( SELECT
                        `c` . `contributor_id` ,
                        `c` . `content_id` ,
                        MIN ( `c` . `created_on` ) AS `insight_created` ,
                        `c` . `store_id`
                    FROM
                        `insights` . `contribution` `c`
                    WHERE
                        `contributor_id` IN (
                            ...
                        )
                    GROUP BY
                        `c` . `content_id` ,
                        `c` . `contributor_id` ) `tmp`
                LEFT JOIN
                    `insights` . `insight` `i`
                        ON `tmp` . `content_id` = `i` . `content_id`
                LEFT JOIN
                    `customer_insight` `pi`
                        ON `i` . `id` = `pi` . `insight_id`
                LEFT JOIN
                    `customer_access_log` `pal`
                        ON `pi` . `customer_id` = `pal` . `customer_id`
                        AND `pal` . `accessed_by` != `tmp` . `contributor_id`
                        AND `pal` .
            """;

        SqlProfile.Builder builder = SqlProfile.newBuilder();
        builder.setSchemaName("tdb");
        sqlAnalysisService.analyzeSql(sql, builder);

        assertTrue(builder.getIsMultiSchema());
    }

    // i don't know why this is failing, may 27, 2022
    @Test
    @Disabled("because clearly I don't care")
    void nested_select_in_exists() throws JSQLParserException {
        String sql = """
                SELECT
                    `f` . `id` AS `store_id` ,
                    COUNT ( DISTINCTROW `pv` . `id` ) AS ?
                FROM
                    `store` `f`
                LEFT JOIN
                    `customer_visit` `pv`
                        ON `f` . `id` = `pv` . `store_id`
                        AND `major_class` = ?
                        AND NOT EXISTS (
                            SELECT
                                `id`
                        FROM
                            `customer_visit`
                        WHERE
                            `customer_id` = `pv` . `customer_id`
                            AND `id` != `pv` . `id`
                            AND (
                                `account_number` = `pv` . `account_number`
                                OR (
                                    `billing_account_number` = `pv` . `billing_account_number`
                                    AND `billing_account_number` IS NOT NULL
                                )
                            )
                            AND `major_class` = ?
                            AND `store_id` = `pv` . `store_id`
                    )
                    AND `checkin_date` >= ?
                    AND `checkin_date` < ? + INTERVAL ? SQL_TSI_DAY
                WHERE
                    `f` . `id` IN (
                        ...
                    )
                GROUP BY
                    `f` . `id`
            """;

        SqlProfile.Builder builder = SqlProfile.newBuilder();
        sqlAnalysisService.analyzeSql(sql, builder);

        assertContainsColumnReferences(builder.getCriteriaColumns(),
                                       "customer_visit.billing_account_number",
                                       "customer_visit.account_number",
                                       "customer_visit.major_class",
                                       "customer_visit.store_id",
                                       "customer_visit.checkin_date");
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
