package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;

import com.github.safetytrick.sqlexplorer.analysis.resolver.ColumnTableResolver;
import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static com.github.safetytrick.sqlexplorer.analysis.Assertions.assertContainsColumnReferences;
import static com.github.safetytrick.sqlexplorer.analysis.Assertions.assertContainsJoinColumns;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class WhereClauseAnalyzerTest {
    private SqlAnalysisService sqlAnalysisService;
    private SqlProfile.Builder builder;
    private ColumnTableResolver columnTableResolver;

    @BeforeEach
    public void setUp() {
        SchemaFixture fixture = SchemaFixture.allFixtures();
        columnTableResolver = fixture.resolver();
        sqlAnalysisService = fixture.service(new WhereClauseAnalyzer(columnTableResolver));
        builder = SqlProfile.newBuilder();
    }

    @Test
    public void testNoRule() throws JSQLParserException {
        String sql = """
            SELECT `id` , `report_column_id` AS `reportColumnId` , `report_column_config_field_type` AS `reportColumnConfigFieldType` , \
            `report_column_config_field_option` AS `columnConfigFieldOption` , \
            VALUE , `created_on` AS `createdOn` , `created_by` AS `createdBy` , `deleted_on` AS `deletedOn` , \
            `deleted_by` AS `deletedBy` FROM `report_column_config_field`\
            """;

        sqlAnalysisService.analyzeSql(sql, builder);

        assertFalse(builder.getAnalysisFailed());
        List<ColumnReference> criteriaColumns = builder.getCriteriaColumns();
        assertNull(criteriaColumns);
    }

    @Test
    public void testNoJoins() throws JSQLParserException {
        String sql = """
            SELECT `id` , `report_column_id` AS `reportColumnId` , `report_column_config_field_type` AS `reportColumnConfigFieldType` , \
            `report_column_config_field_option` AS `columnConfigFieldOption` , \
            VALUE , `created_on` AS `createdOn` , `created_by` AS `createdBy` , `deleted_on` AS `deletedOn` , \
            `deleted_by` AS `deletedBy` FROM `report_column_config_field` \
            WHERE `report_column_id` = ?\
            """;

        sqlAnalysisService.analyzeSql(sql, builder);

        List<ColumnReference> criteriaColumns = builder.getCriteriaColumns();
        assertNotNull(criteriaColumns);

        assertThat(criteriaColumns)
            .hasSize(1)
            .anyMatch(column -> column.equalsNormalized("report_column_config_field.report_column_id"));
    }

    @Test
    public void testBigOlQuery() throws JSQLParserException {
        String sql = """
            SELECT `cn` . `id` , `cnc` . `binary_content` FROM `org_alert` `cn` \
            INNER JOIN `org_alert_content` `cnc` ON `cn` . `id` = `cnc` . `id` \
            WHERE `cn` . `completed_on` IS NULL \
            AND `cn` . `begin_processing` IS NULL \
            AND `cn` . `type` = ? AND `cn` . `event_time` > NOW ( ) - INTERVAL 6 SQL_TSI_DAY \
            AND `cn` . `request_time` > NOW ( ) - INTERVAL 0 SQL_TSI_HOUR \
            AND `cn` . `sent_to_branch_id` IN (?)\
            """;

        sqlAnalysisService.analyzeSql(sql, builder);

        List<ColumnReference> criteriaColumns = builder.getCriteriaColumns();
        assertNotNull(criteriaColumns);

        assertThat(criteriaColumns).anyMatch(column -> column.equalsNormalized("org_alert.completed_on"));

    }

    @Test
    public void testMemberSession() throws JSQLParserException {
        String sql = """
            SELECT `pvd`.`branch_id`
            FROM `member_session` `pvd`
                   LEFT JOIN `branch` `f` ON `f`.`id` = `pvd`.`branch_id`
                   LEFT JOIN `branch_config` `fc` ON (`f`.`id` = `fc`.`id` AND `fc`.`name` = ?)
            WHERE `pvd`.`member_id` = ?
              AND `pvd`.`checkin_date` >= SUBDATE(NOW(), `IFNULL`(`fc`.`value`, ?))
              AND `f`.`type` IN (?)
            GROUP BY `pvd`.`branch_id`
            """;

        sqlAnalysisService.analyzeSql(sql, builder);

        List<ColumnReference> criteriaColumns = builder.getCriteriaColumns();
        assertNotNull(criteriaColumns);

        assertThat(criteriaColumns).anyMatch(column -> column.equalsNormalized("branch.type"));
        assertThat(criteriaColumns).anyMatch(column -> column.equalsNormalized("member_session.member_id"));
        assertThat(criteriaColumns).anyMatch(column -> column.equalsNormalized("member_session.checkin_date"));
        assertThat(criteriaColumns).anyMatch(column -> column.equalsNormalized("branch_config.value"));
    }

    @Test
    public void testMissingAlias() throws JSQLParserException {
        String sql = """
                SELECT
                    `agent` . *
                FROM
                    `member_identifier` `pi`
                INNER JOIN
                    `member_branch_relationship` `agent`
                        ON `agent` . `identifier` = `pi` . `identifier`
                        AND `agent` . `assigning_auth` = `pi` . `branch_id`
                WHERE
                    `pi` . `member_id` = ?
                    AND (
                        `effective_on` IS NULL
                        OR `effective_on` <= NOW ( )
                    ) \
            """;

        sqlAnalysisService.analyzeSql(sql, builder);

        List<ColumnReference> criteriaColumns = builder.getCriteriaColumns();
        assertNotNull(criteriaColumns);

        assertThat(criteriaColumns).anyMatch(column -> column.equalsNormalized("member_identifier.member_id"));
        assertThat(criteriaColumns).anyMatch(column -> column.equalsNormalized("member_branch_relationship.effective_on"));
    }

    @Test
    public void testSubSelect() throws JSQLParserException {
        String sql = """

                SELECT
                    `pcg1` . `id` ,
                    `pcg1` . `member_id` AS `memberId` ,
                    `pcg1` . `branch_id` AS `branchId` ,
                    `pcg1` . `using_guideline_id` AS `usingGuidelineId` ,
                    `pcg1` . `custom_fields` AS `customFields` ,
                    `pcg1` . `content_action` AS `contentAction` ,
                    `pcg1` . `data_source` AS `dataSourceId` ,
                    `pcg1` . `type` ,
                    `pcg1` . `category` ,
                    `pcg1` . `created_by` AS `createdById` ,
                    `pcg1` . `created_on` AS `createdOn`
                FROM
                    `member_care_guideline` AS `pcg1`
                INNER JOIN
                    (
                        SELECT
                            `member_id` ,
                            MIN ( `created_on` ) AS `created_on`
                        FROM
                            `member_care_guideline`
                        WHERE
                            `member_id` IN (
                                ...
                            )
                            AND `content_action` IN (
                                ...
                            )
                            AND `branch_id` = ?
                        GROUP BY
                            `member_id`
                    ) AS `pcg2`
                        ON `pcg1` . `member_id` = `pcg2` . `member_id`
                        AND `pcg1` . `created_on` = `pcg2` . `created_on` \
            """;

        sqlAnalysisService.analyzeSql(sql, builder);

        List<ColumnReference> criteriaColumns = builder.getCriteriaColumns();
        assertThat(criteriaColumns).anyMatch(column -> column.equalsNormalized("member_care_guideline.member_id"));
        assertThat(criteriaColumns).anyMatch(column -> column.equalsNormalized("member_care_guideline.content_action"));
        assertThat(criteriaColumns).anyMatch(column -> column.equalsNormalized("member_care_guideline.branch_id"));
    }

    @Test
    public void testSubSelectJoinInWhere() throws JSQLParserException {
        String sql = """

            SELECT
                    `temp` . *
                FROM
                    `member_session` `mother_session`
                JOIN
                    (
                        SELECT
                            `pv` . * ,
                            `ins` . `member_id` AS `mother`
                        FROM
                            `member_plan` `ins` ,
                            `member_plan` `ins2` ,
                            `member_ident` `ident` ,
                            `member_session_plan` `pvi` ,
                            `member_session` `pv`
                        WHERE
                            `pv` . `id` = `pvi` . `member_session_id`
                            AND `pv` . `checkout_date` >= ?
                            AND `pv` . `checkout_date` <= ?
                            AND (
                                `pv` . `session_type` = ?
                                OR `pv` . `main_class` IN (
                                    ?
                                )
                            )
                            AND `ident` . `date_of_signup` > DATE_SUB ( NOW ( ) , INTERVAL ? SQL_TSI_DAY )
                            AND `ins2` . `member_id` = `ident` . `member_id`
                            AND `ins` . `branch_id` = ?
                            AND `ins2` . `account_number` = `ins` . `account_number`
                            AND `pvi` . `member_plan_id` = `ins2` . `id`
                            AND (
                                DATE ( `pv` . `checkin_date` ) <= `ins` . `service_end`
                                OR `ins` . `service_end` IS NULL
                            )
                            AND (
                                DATE ( `pv` . `checkin_date` ) >= `ins` . `service_start`
                                OR `ins` . `service_start` IS NULL
                            )
                            AND `ins2` . `branch_id` IS NULL
                        GROUP BY
                            `pv` . `id`
                    ) AS `temp`
                        ON `temp` . `mother` = `mother_session` . `member_id`
                WHERE
                    `mother_session` . `member_id` = `temp`"""
            ;

        sqlAnalysisService.analyzeSql(sql, builder);

        List<JoinColumns> joinColumns = builder.getJoinColumns();
        assertContainsJoinColumns(joinColumns, "member_session.id", "member_session_plan.member_session_id");
    }

    @Test
    public void testTempTableSubSelect() throws JSQLParserException {
        String sql = """
                SELECT
                    `dup` . `id`
                FROM
                    ( SELECT
                        `alert_rule_id` ,
                        `m` . `member_id` ,
                        MIN ( `m` . `created_on` ) AS FIRST ,
                        COUNT ( * ) AS `count`
                    FROM
                        `alert_rule` `c`
                    INNER JOIN
                        `alert_rule_met` `m`
                            ON `m` . `alert_rule_id` = `c` . `id`
                    WHERE
                        `c` . `name` = ?
                        AND `m` . `created_on` >= ?
                    GROUP BY
                        `m` . `alert_rule_id` ,
                        `m` . `member_id`
                    HAVING
                        `count` > ? ) `bad`
                INNER JOIN
                    `alert_rule_met` `dup`
                        ON `dup` . `member_id` = `bad` . `member_id`
                        AND `dup` . `alert_rule_id` = `bad` . `alert_rule_id`
                WHERE
                    `dup` . `created_on` >= ?
                    AND `dup` . `created_on` != FIRST \
            """;

        Statement statement = CCJSqlParserUtil.parse(sql);

        WhereClauseAnalyzer whereClauseAnalyzer = new WhereClauseAnalyzer(columnTableResolver);
        JoinColumnAnalyzer joinColumnAnalyzer = new JoinColumnAnalyzer(columnTableResolver);

        whereClauseAnalyzer.analyze(statement, builder);
        joinColumnAnalyzer.analyze(statement, builder);

        List<JoinColumns> joinColumns = builder.getJoinColumns();
        assertContainsJoinColumns(joinColumns, "alert_rule_met.member_id", "alert_rule_met.member_id");
    }

    @Test
    void sub_select_find_columns_for_table() throws JSQLParserException {
        String sql = """
                INSERT
                INTO
                    `deleted_member_referral` (
                        `id` , `member_id`, `deleted_on`
                    ) SELECT
                        `id` ,
                        `member_id` ,
                        ?
                    FROM
                        `member_referral`
                    WHERE
                        `id` = ? \
            """;

        Statement statement = CCJSqlParserUtil.parse(sql);

        WhereClauseAnalyzer whereClauseAnalyzer = new WhereClauseAnalyzer(columnTableResolver);

        whereClauseAnalyzer.analyze(statement, builder);

        assertThat(builder.getCriteriaColumns())
            .anyMatch(other -> ColumnReference.fromString("member_referral.id").equalsNormalized(other));
    }

    @Test
    void sub_select_in_list() throws JSQLParserException {
        String sql = """
                SELECT
                    *
                FROM
                    `member_session`
                WHERE
                    `member_id` IN (
                        SELECT
                            `member_id`
                        FROM
                            `member_referral`
                        WHERE
                            `managing_branch_id` = ?
                    )
                    AND `checkin_date` <= ? \
            """;

        Statement statement = CCJSqlParserUtil.parse(sql);

        WhereClauseAnalyzer whereClauseAnalyzer = new WhereClauseAnalyzer(columnTableResolver);

        whereClauseAnalyzer.analyze(statement, builder);

        assertFalse(builder.getAnalysisFailed(), builder.getAnalysisFailureReason());

        assertContainsColumnReferences(builder.getCriteriaColumns(), "member_session.checkin_date");
        assertContainsColumnReferences(builder.getCriteriaColumns(), "member_session.member_id");
        assertContainsColumnReferences(builder.getCriteriaColumns(), "member_referral.managing_branch_id");

    }

    @Disabled("not sure about the easiest way to support this")
    @Test
    void select_name_of_temp_table() throws JSQLParserException {
        String sql = """
                SELECT
                    *
                FROM
                    ( SELECT
                        `id` ,
                        `member_id` ,
                        `created_on` ,
                        `identifier` ,
                        @a := IF ( @a = `member_id` ,
                        @a + ?,
                        ? ) AS `day_rank` ,
                        @a := `member_id` AS `pat_id2`
                    FROM
                        `member_identifier`
                    WHERE
                        `branch_id` = ?
                        AND TYPE = ?
                    ORDER BY
                        `member_id` ,
                        `created_on` DESC ) `T1`
                WHERE
                    `T1` . `day_rank` != ? \
            """;

        Statement statement = CCJSqlParserUtil.parse(sql);

        WhereClauseAnalyzer whereClauseAnalyzer = new WhereClauseAnalyzer(columnTableResolver);

        whereClauseAnalyzer.analyze(statement, builder);

        assertThat(builder.getCriteriaColumns())
            .contains(ColumnReference.fromString("member_identifier.branch_id"))
            .contains(ColumnReference.fromString("member_identifier.type"));
    }

    @Test
    void resolve_alias_in_subquery() throws JSQLParserException {
        String sql = """
                SELECT
                    `f` . `name` AS `Branch` ,
                    `fi` . `identifier`
                FROM
                    `branch` `f`
                JOIN
                    `branch_identifier` `fi`
                        ON `f` . `id` = `fi` . `branch_id`
                WHERE
                    `fi` . `type` = ?
                    AND EXISTS (
                        SELECT
                            *
                        FROM
                            `member_session`
                        WHERE
                            `id` LIKE ?
                            AND `branch_id` = `f` . `id`
                            AND `checkin_date` >= CURDATE ( ) - INTERVAL ? WEEK
                    )
                ORDER BY
                    `f` . `state` ,
                    `f` . `name` \
            """;

        Statement statement = CCJSqlParserUtil.parse(sql);

        WhereClauseAnalyzer whereClauseAnalyzer = new WhereClauseAnalyzer(columnTableResolver);

        whereClauseAnalyzer.analyze(statement, builder);

        assertContainsColumnReferences(builder.getCriteriaColumns(),"branch_identifier.type");
        assertContainsColumnReferences(builder.getCriteriaColumns(),"member_session.branch_id");
        assertContainsColumnReferences(builder.getCriteriaColumns(),"member_session.id");
        assertContainsColumnReferences(builder.getCriteriaColumns(),"member_session.checkin_date");
    }

    @Test
    void ORG() throws JSQLParserException {
        String sql = """
                SELECT
                    `report_column` . `id` ,
                    `report_id` AS `reportId` ,
                    `report_column` . `created_on` AS `createdOn` ,
                    `org` . `id` AS `createdBy` ,
                    `CONCAT` ( `org` . `first_name` ,
                    ? ,
                    `org` . `last_name` ) AS `createdByName` ,
                    `report_column` . `deleted_on` AS `deletedOn` ,
                    `report_column` . `deleted_by` AS `deletedBy`
                FROM
                    `report_column`
                JOIN
                    `report`
                        ON `report_column` . `report_id` = `report` . `id`
                JOIN
                    ORG
                        ON `report_column` . `created_by` = `org` . `id`
                WHERE
                    `report_id` = ?
                    AND `report` . `branch_id` = ? \
            """;

        Statement statement = CCJSqlParserUtil.parse(sql);

        WhereClauseAnalyzer whereClauseAnalyzer = new WhereClauseAnalyzer(columnTableResolver);

        whereClauseAnalyzer.analyze(statement, builder);
    }

    @Test
    void subjoin() throws JSQLParserException {
        String sql = """
            SELECT `num`.*
            FROM ( `alert_rule` `n`
                INNER JOIN
                `alert_rule_met` `num`
                ON `num`.`alert_rule_id` = `n`.`id`
                    AND `n`.`name` = ?
                     )
                     LEFT JOIN
                 (
                     `alert_rule` `d`
                         INNER JOIN
                         `alert_rule_met` `den`
                         ON `den`.`alert_rule_id` = `d`.`id`
                             AND `d`.`name` = ?
                     )
                 ON `den`.`branch_id` = `num`.`branch_id`
                     AND `den`.`member_id` = `num`.`member_id`
            WHERE `num`.`created_on` >= ?
              AND `den`.`member_id` IS NULL
            ORDER BY `num`.`created_on`""";

        Statement statement = CCJSqlParserUtil.parse(sql);

        WhereClauseAnalyzer whereClauseAnalyzer = new WhereClauseAnalyzer(columnTableResolver);

        whereClauseAnalyzer.analyze(statement, builder);

        assertThat(builder.getCriteriaColumns()).anyMatch(column -> column.equalsNormalized("alert_rule_met.created_on"));
        assertThat(builder.getCriteriaColumns()).anyMatch(column -> column.equalsNormalized("alert_rule_met.member_id"));
    }

    @Disabled("need to improve scoping to fix this")
    @Test
    void unambiguous_subquery() throws JSQLParserException {
        String sql = """
                SELECT
                    * ,
                    ( SELECT
                        NAME
                    FROM
                        `branch`
                    WHERE
                        `id` = `ds` . `branch_id` )
                FROM
                    `data_source` `ds`
                WHERE
                    `id` = ? \
            """;

        Statement statement = CCJSqlParserUtil.parse(sql);

        WhereClauseAnalyzer whereClauseAnalyzer = new WhereClauseAnalyzer(columnTableResolver);

        whereClauseAnalyzer.analyze(statement, builder);
    }

    @Test
    void qualified_branch_id() throws JSQLParserException {
        String sql = """
                SELECT
                    `fc` . `id` ,
                    `fc` . `branch_id` AS `branchId` ,
                    `fc` . `name` AS NAME ,
                    `fc` . `value` AS VALUE ,
                    `cliCre` . `username` AS `createdBy` ,
                    `fc` . `created_on` AS `createdOn` ,
                    `cliDel` . `username` AS `deletedBy` ,
                    `fc` . `deleted_on` AS `deletedOn`
                FROM
                    `branch_config` `fc`
                LEFT JOIN
                    ORG `cliCre`
                        ON `fc` . `created_by` = `cliCre` . `id`
                LEFT JOIN
                    ORG `cliDel`
                        ON `fc` . `deleted_by` = `cliDel` . `id`
                WHERE
                    `branch_id` = ?
                ORDER BY
                    `fc` . `created_on` DESC LIMIT ? \
            """;

        Statement statement = CCJSqlParserUtil.parse(sql);

        WhereClauseAnalyzer whereClauseAnalyzer = new WhereClauseAnalyzer(columnTableResolver);

        whereClauseAnalyzer.analyze(statement, builder);

        assertFalse(builder.getAnalysisFailed());
    }

    @Test
    void cross_schema_join() throws JSQLParserException {
        String sql = """
                SELECT
                    `report_config_field` . `id` ,
                    `report_id` AS `reportId` ,
                    `report_config_field_type` AS `configFieldType` ,
                    VALUE ,
                    `report_config_field` . `created_on` AS `createdOn` ,
                    `org` . `id` AS `createdBy` ,
                    `CONCAT` ( `org` . `first_name` ,
                    ? ,
                    `org` . `last_name` ) AS `createdByName` ,
                    `CONCAT` ( `org2` . `first_name` ,
                    ? ,
                    `org2` . `last_name` ) AS `deletedByName` ,
                    `report_config_field` . `deleted_on` AS `deletedOn` ,
                    `report_config_field` . `deleted_by` AS `deletedBy`
                FROM
                    `report_config_field`
                JOIN
                    `report`
                        ON `report_config_field` . `report_id` = `report` . `id`
                JOIN
                    `tdb` . `org` AS ORG
                        ON `report_config_field` . `created_by` = `org` . `id`
                LEFT JOIN
                    `tdb` . `org` AS `org2`
                        ON `report_config_field` . `deleted_by` = `org2` . `id`
                WHERE
                    `report_id` = ? \
            """;

        Statement statement = CCJSqlParserUtil.parse(sql);

        WhereClauseAnalyzer whereClauseAnalyzer = new WhereClauseAnalyzer(columnTableResolver);
        JoinColumnAnalyzer joinColumnAnalyzer = new JoinColumnAnalyzer(columnTableResolver);

        whereClauseAnalyzer.analyze(statement, builder);
        joinColumnAnalyzer.analyze(statement, builder);

        assertFalse(builder.getAnalysisFailed());


        assertThat(builder.getCriteriaColumns()).anyMatch(column -> column.equalsNormalized("report_config_field.report_id"));

        List<JoinColumns> joinColumns = builder.getJoinColumns();

        assertContainsJoinColumns(joinColumns, "report_config_field.report_id", "report.id");
        assertContainsJoinColumns(joinColumns, "report_config_field.created_by", "tdb.org.id");
        assertContainsJoinColumns(joinColumns, "report_config_field.created_by", "tdb.org.id");
        assertContainsJoinColumns(joinColumns, "report_config_field.deleted_by", "tdb.org.id");
    }

    @Test
    void expression_references_true() throws JSQLParserException {
        String sql = """
            SELECT `nd`.*
            FROM `alert_rule_destination` `ncd`
                     JOIN `alert_destination` `nd` ON `ncd`.`alert_destination_id` = `nd`.`id`
                     JOIN `alert_rule` `nc` ON `ncd`.`alert_rule_id` = `nc`.`id`
            WHERE `ncd`.`alert_rule_id` = ?
              AND `ncd`.`deleted_on` IS NULL
              AND `nd`.`deleted_on` IS NULL
              AND `nd`.`enabled` = TRUE
              AND `nd`.`enabled_on` <= ?""";

        builder.setSchemaName("tdb");
        sqlAnalysisService.analyzeSql(sql, builder);

        List<ColumnReference> criteriaColumns = builder.getCriteriaColumns();

        assertContainsColumnReferences(criteriaColumns,
                                       "alert_rule_destination.alert_rule_id",
                                       "alert_rule_destination.deleted_on",
                                       "alert_destination.deleted_on",
                                       "alert_destination.enabled",
                                       "alert_destination.enabled_on");

    }



    @Test
    void table_for_branch_id_not_found() throws JSQLParserException {
        String sql = """
                SELECT
                    `fc` . `id` ,
                    `fc` . `branch_id` AS `branchId` ,
                    `fc` . `name` AS NAME ,
                    `fc` . `value` AS VALUE ,
                    `cliCre` . `username` AS `createdBy` ,
                    `fc` . `created_on` AS `createdOn` ,
                    `cliDel` . `username` AS `deletedBy` ,
                    `fc` . `deleted_on` AS `deletedOn`
                FROM
                    `branch_config` `fc`
                LEFT JOIN
                    ORG `cliCre`
                        ON `fc` . `created_by` = `cliCre` . `id`
                LEFT JOIN
                    ORG `cliDel`
                        ON `fc` . `deleted_by` = `cliDel` . `id`
                WHERE
                    `branch_id` = ?
                ORDER BY
                    `fc` . `created_on` DESC LIMIT ? \
            """;

        sqlAnalysisService.analyzeSql(sql, builder);

        List<ColumnReference> criteriaColumns = builder.getCriteriaColumns();
        assertContainsColumnReferences(criteriaColumns, "branch_config.branch_id");
    }

    @Test
    void multiple_tables_one_qualified_one_not() throws JSQLParserException {
        String sql = """
                SELECT
                    `c` . `contributor_id` ,
                    `c` . `content_id` ,
                    MIN ( `c` . `created_on` ) AS `insight_created` ,
                    `c` . `branch_id` ,
                    ( SELECT
                        COUNT ( `id` )
                    FROM
                        `analytics` . `contribution`
                    WHERE
                        `content_id` = `c` . `content_id`
                        AND `contributor_id` != `c` . `contributor_id`
                        AND `branch_id` != `c` . `branch_id` ) AS `updates_at_different_branch` ,
                    ( SELECT
                        COUNT ( `id` )
                    FROM
                        `analytics` . `contribution`
                    WHERE
                        `content_id` = `c` . `content_id`
                        AND `contributor_id` != `c` . `contributor_id`
                        AND `branch_id` = `c` . `branch_id` ) AS `updates_at_same_branch` ,
                    ( SELECT
                        GROUP_CONCAT ( DISTINCTROW `contributor_id` SEPARATOR ? )
                    FROM
                        `analytics` . `contribution`
                    WHERE
                        `content_id` = `c` . `content_id`
                        AND `contributor_id` != `c` . `contributor_id` ) AS `collaborating_authors`
                FROM
                    `analytics` . `contribution` `c`
                WHERE
                    `contributor_id` IN (
                        ...
                    )
                GROUP BY
                    `c` . `content_id` ,
                    `c` . `contributor_id`\
            """;

        sqlAnalysisService.analyzeSql(sql, builder);

        List<ColumnReference> criteriaColumns = builder.getCriteriaColumns();
        assertContainsColumnReferences(criteriaColumns, "analytics.contribution.contributor_id");
    }


}
