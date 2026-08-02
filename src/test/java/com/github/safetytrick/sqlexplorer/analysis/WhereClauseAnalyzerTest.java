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
    private ColumnTableResolver columnTableResolver;
    private SqlProfile.Builder builder;

    @BeforeEach
    public void setUp() {
        columnTableResolver = new ColumnTableResolver(TestSchemas.all());
        sqlAnalysisService = new SqlAnalysisService(new WhereClauseAnalyzer(columnTableResolver));
        builder = SqlProfile.newBuilder();
    }

    @Test
    public void testNoCriteria() throws JSQLParserException {
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
            SELECT `cn` . `id` , `cnc` . `binary_content` FROM `tenant_notification` `cn` \
            INNER JOIN `tenant_notification_content` `cnc` ON `cn` . `id` = `cnc` . `id` \
            WHERE `cn` . `completed_on` IS NULL \
            AND `cn` . `begin_processing` IS NULL \
            AND `cn` . `type` = ? AND `cn` . `event_time` > NOW ( ) - INTERVAL 6 SQL_TSI_DAY \
            AND `cn` . `request_time` > NOW ( ) - INTERVAL 0 SQL_TSI_HOUR \
            AND `cn` . `sent_to_store_id` IN (?)\
            """;

        sqlAnalysisService.analyzeSql(sql, builder);

        List<ColumnReference> criteriaColumns = builder.getCriteriaColumns();
        assertNotNull(criteriaColumns);

        assertThat(criteriaColumns).anyMatch(column -> column.equalsNormalized("tenant_notification.completed_on"));

    }

    @Test
    public void testCustomerVisit() throws JSQLParserException {
        String sql = """
            SELECT `pvd`.`store_id`
            FROM `customer_visit` `pvd`
                   LEFT JOIN `store` `f` ON `f`.`id` = `pvd`.`store_id`
                   LEFT JOIN `store_config` `fc` ON (`f`.`id` = `fc`.`id` AND `fc`.`name` = ?)
            WHERE `pvd`.`customer_id` = ?
              AND `pvd`.`checkin_date` >= SUBDATE(NOW(), `IFNULL`(`fc`.`value`, ?))
              AND `f`.`type` IN (?)
            GROUP BY `pvd`.`store_id`
            """;

        sqlAnalysisService.analyzeSql(sql, builder);

        List<ColumnReference> criteriaColumns = builder.getCriteriaColumns();
        assertNotNull(criteriaColumns);

        assertThat(criteriaColumns).anyMatch(column -> column.equalsNormalized("store.type"));
        assertThat(criteriaColumns).anyMatch(column -> column.equalsNormalized("customer_visit.customer_id"));
        assertThat(criteriaColumns).anyMatch(column -> column.equalsNormalized("customer_visit.checkin_date"));
        assertThat(criteriaColumns).anyMatch(column -> column.equalsNormalized("store_config.value"));
    }

    @Test
    public void testMissingAlias() throws JSQLParserException {
        String sql = """
                SELECT
                    `csr` . *
                FROM
                    `customer_identifier` `pi`
                INNER JOIN
                    `customer_store_relationship` `csr`
                        ON `csr` . `identifier` = `pi` . `identifier`
                        AND `csr` . `assigning_auth` = `pi` . `store_id`
                WHERE
                    `pi` . `customer_id` = ?
                    AND (
                        `effective_on` IS NULL
                        OR `effective_on` <= NOW ( )
                    ) \
            """;

        sqlAnalysisService.analyzeSql(sql, builder);

        List<ColumnReference> criteriaColumns = builder.getCriteriaColumns();
        assertNotNull(criteriaColumns);

        assertThat(criteriaColumns).anyMatch(column -> column.equalsNormalized("customer_identifier.customer_id"));
        assertThat(criteriaColumns).anyMatch(column -> column.equalsNormalized("customer_store_relationship.effective_on"));
    }

    @Test
    public void testSubSelect() throws JSQLParserException {
        String sql = """

                SELECT
                    `pcg1` . `id` ,
                    `pcg1` . `customer_id` AS `customerId` ,
                    `pcg1` . `store_id` AS `storeId` ,
                    `pcg1` . `using_guideline_id` AS `usingGuidelineId` ,
                    `pcg1` . `custom_fields` AS `customFields` ,
                    `pcg1` . `content_action` AS `contentAction` ,
                    `pcg1` . `data_source` AS `dataSourceId` ,
                    `pcg1` . `type` ,
                    `pcg1` . `category` ,
                    `pcg1` . `created_by` AS `createdById` ,
                    `pcg1` . `created_on` AS `createdOn`
                FROM
                    `customer_care_guideline` AS `pcg1`
                INNER JOIN
                    (
                        SELECT
                            `customer_id` ,
                            MIN ( `created_on` ) AS `created_on`
                        FROM
                            `customer_care_guideline`
                        WHERE
                            `customer_id` IN (
                                ...
                            )
                            AND `content_action` IN (
                                ...
                            )
                            AND `store_id` = ?
                        GROUP BY
                            `customer_id`
                    ) AS `pcg2`
                        ON `pcg1` . `customer_id` = `pcg2` . `customer_id`
                        AND `pcg1` . `created_on` = `pcg2` . `created_on` \
            """;

        sqlAnalysisService.analyzeSql(sql, builder);

        List<ColumnReference> criteriaColumns = builder.getCriteriaColumns();
        assertThat(criteriaColumns).anyMatch(column -> column.equalsNormalized("customer_care_guideline.customer_id"));
        assertThat(criteriaColumns).anyMatch(column -> column.equalsNormalized("customer_care_guideline.content_action"));
        assertThat(criteriaColumns).anyMatch(column -> column.equalsNormalized("customer_care_guideline.store_id"));
    }

    @Test
    public void testSubSelectJoinInWhere() throws JSQLParserException {
        String sql = """

            SELECT
                    `temp` . *
                FROM
                    `customer_visit` `mother_visit`
                JOIN
                    (
                        SELECT
                            `pv` . * ,
                            `ins` . `customer_id` AS `mother`
                        FROM
                            `customer_membership` `ins` ,
                            `customer_membership` `ins2` ,
                            `customer_ident` `ident` ,
                            `customer_visit_membership` `pvi` ,
                            `customer_visit` `pv`
                        WHERE
                            `pv` . `id` = `pvi` . `customer_visit_id`
                            AND `pv` . `checkout_date` >= ?
                            AND `pv` . `checkout_date` <= ?
                            AND (
                                `pv` . `visit_type` = ?
                                OR `pv` . `major_class` IN (
                                    ?
                                )
                            )
                            AND `ident` . `date_of_birth` > DATE_SUB ( NOW ( ) , INTERVAL ? SQL_TSI_DAY )
                            AND `ins2` . `customer_id` = `ident` . `customer_id`
                            AND `ins` . `store_id` = ?
                            AND `ins2` . `account_number` = `ins` . `account_number`
                            AND `pvi` . `customer_membership_id` = `ins2` . `id`
                            AND (
                                DATE ( `pv` . `checkin_date` ) <= `ins` . `service_end`
                                OR `ins` . `service_end` IS NULL
                            )
                            AND (
                                DATE ( `pv` . `checkin_date` ) >= `ins` . `service_start`
                                OR `ins` . `service_start` IS NULL
                            )
                            AND `ins2` . `store_id` IS NULL
                        GROUP BY
                            `pv` . `id`
                    ) AS `temp`
                        ON `temp` . `mother` = `mother_visit` . `customer_id`
                WHERE
                    `mother_visit` . `customer_id` = `temp`"""
            // + "."; // todo this was parsed by earlier versions of the jsqlparser library,
            //  it's certainly not valid sql but handling partial sql is a goal of this tool
            ;

        sqlAnalysisService.analyzeSql(sql, builder);

        List<JoinColumns> joinColumns = builder.getJoinColumns();
        assertContainsJoinColumns(joinColumns, "customer_visit.id", "customer_visit_membership.customer_visit_id");
    }

    @Test
    public void testTempTableSubSelect() throws JSQLParserException {
        String sql = """
                SELECT
                    `dup` . `id`
                FROM
                    ( SELECT
                        `notif_criteria_id` ,
                        `m` . `customer_id` ,
                        MIN ( `m` . `created_on` ) AS FIRST ,
                        COUNT ( * ) AS `count`
                    FROM
                        `notif_criteria` `c`
                    INNER JOIN
                        `notif_criteria_met` `m`
                            ON `m` . `notif_criteria_id` = `c` . `id`
                    WHERE
                        `c` . `name` = ?
                        AND `m` . `created_on` >= ?
                    GROUP BY
                        `m` . `notif_criteria_id` ,
                        `m` . `customer_id`
                    HAVING
                        `count` > ? ) `bad`
                INNER JOIN
                    `notif_criteria_met` `dup`
                        ON `dup` . `customer_id` = `bad` . `customer_id`
                        AND `dup` . `notif_criteria_id` = `bad` . `notif_criteria_id`
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
        assertContainsJoinColumns(joinColumns, "notif_criteria_met.customer_id", "notif_criteria_met.customer_id");
    }

    @Test
    void sub_select_find_columns_for_table() throws JSQLParserException {
        String sql = """
                INSERT
                INTO
                    `deleted_customer_referral` (
                        `id` , `customer_id`, `deleted_on`
                    ) SELECT
                        `id` ,
                        `customer_id` ,
                        ?
                    FROM
                        `customer_referral`
                    WHERE
                        `id` = ? \
            """;

        Statement statement = CCJSqlParserUtil.parse(sql);

        WhereClauseAnalyzer whereClauseAnalyzer = new WhereClauseAnalyzer(columnTableResolver);

        whereClauseAnalyzer.analyze(statement, builder);

        assertThat(builder.getCriteriaColumns())
            .anyMatch(other -> ColumnReference.fromString("customer_referral.id").equalsNormalized(other));
    }

    @Test
    void sub_select_in_list() throws JSQLParserException {
        String sql = """
                SELECT
                    *
                FROM
                    `customer_visit`
                WHERE
                    `customer_id` IN (
                        SELECT
                            `customer_id`
                        FROM
                            `customer_referral`
                        WHERE
                            `managing_store_id` = ?
                    )
                    AND `checkin_date` <= ? \
            """;

        Statement statement = CCJSqlParserUtil.parse(sql);

        WhereClauseAnalyzer whereClauseAnalyzer = new WhereClauseAnalyzer(columnTableResolver);

        whereClauseAnalyzer.analyze(statement, builder);

        assertFalse(builder.getAnalysisFailed(), builder.getAnalysisFailureReason());

        assertContainsColumnReferences(builder.getCriteriaColumns(), "customer_visit.checkin_date");
        assertContainsColumnReferences(builder.getCriteriaColumns(), "customer_visit.customer_id");
        assertContainsColumnReferences(builder.getCriteriaColumns(), "customer_referral.managing_store_id");

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
                        `customer_id` ,
                        `created_on` ,
                        `identifier` ,
                        @a := IF ( @a = `customer_id` ,
                        @a + ?,
                        ? ) AS `day_rank` ,
                        @a := `customer_id` AS `pat_id2`
                    FROM
                        `customer_identifier`
                    WHERE
                        `store_id` = ?
                        AND TYPE = ?
                    ORDER BY
                        `customer_id` ,
                        `created_on` DESC ) `T1`
                WHERE
                    `T1` . `day_rank` != ? \
            """;

        Statement statement = CCJSqlParserUtil.parse(sql);

        WhereClauseAnalyzer whereClauseAnalyzer = new WhereClauseAnalyzer(columnTableResolver);

        whereClauseAnalyzer.analyze(statement, builder);

        assertThat(builder.getCriteriaColumns())
            .contains(ColumnReference.fromString("customer_identifier.store_id"))
            .contains(ColumnReference.fromString("customer_identifier.type"));
    }

    @Test
    void resolve_alias_in_subquery() throws JSQLParserException {
        String sql = """
                SELECT
                    `f` . `name` AS `Store` ,
                    `fi` . `identifier`
                FROM
                    `store` `f`
                JOIN
                    `store_identifier` `fi`
                        ON `f` . `id` = `fi` . `store_id`
                WHERE
                    `fi` . `type` = ?
                    AND EXISTS (
                        SELECT
                            *
                        FROM
                            `customer_visit`
                        WHERE
                            `id` LIKE ?
                            AND `store_id` = `f` . `id`
                            AND `checkin_date` >= CURDATE ( ) - INTERVAL ? WEEK
                    )
                ORDER BY
                    `f` . `state` ,
                    `f` . `name` \
            """;

        Statement statement = CCJSqlParserUtil.parse(sql);

        WhereClauseAnalyzer whereClauseAnalyzer = new WhereClauseAnalyzer(columnTableResolver);

        whereClauseAnalyzer.analyze(statement, builder);

        assertContainsColumnReferences(builder.getCriteriaColumns(),"store_identifier.type");
        assertContainsColumnReferences(builder.getCriteriaColumns(),"customer_visit.store_id");
        assertContainsColumnReferences(builder.getCriteriaColumns(),"customer_visit.id");
        assertContainsColumnReferences(builder.getCriteriaColumns(),"customer_visit.checkin_date");
    }

    @Test
    void TENANT() throws JSQLParserException {
        String sql = """
                SELECT
                    `report_column` . `id` ,
                    `report_id` AS `reportId` ,
                    `report_column` . `created_on` AS `createdOn` ,
                    `tenant` . `id` AS `createdBy` ,
                    `CONCAT` ( `tenant` . `first_name` ,
                    ? ,
                    `tenant` . `last_name` ) AS `createdByName` ,
                    `report_column` . `deleted_on` AS `deletedOn` ,
                    `report_column` . `deleted_by` AS `deletedBy`
                FROM
                    `report_column`
                JOIN
                    `report`
                        ON `report_column` . `report_id` = `report` . `id`
                JOIN
                    TENANT
                        ON `report_column` . `created_by` = `tenant` . `id`
                WHERE
                    `report_id` = ?
                    AND `report` . `store_id` = ? \
            """;

        Statement statement = CCJSqlParserUtil.parse(sql);

        WhereClauseAnalyzer whereClauseAnalyzer = new WhereClauseAnalyzer(columnTableResolver);

        whereClauseAnalyzer.analyze(statement, builder);
    }

    @Test
    void subjoin() throws JSQLParserException {
        String sql = """
            SELECT `num`.*
            FROM ( `notif_criteria` `n`
                INNER JOIN
                `notif_criteria_met` `num`
                ON `num`.`notif_criteria_id` = `n`.`id`
                    AND `n`.`name` = ?
                     )
                     LEFT JOIN
                 (
                     `notif_criteria` `d`
                         INNER JOIN
                         `notif_criteria_met` `den`
                         ON `den`.`notif_criteria_id` = `d`.`id`
                             AND `d`.`name` = ?
                     )
                 ON `den`.`store_id` = `num`.`store_id`
                     AND `den`.`customer_id` = `num`.`customer_id`
            WHERE `num`.`created_on` >= ?
              AND `den`.`customer_id` IS NULL
            ORDER BY `num`.`created_on`""";

        Statement statement = CCJSqlParserUtil.parse(sql);

        WhereClauseAnalyzer whereClauseAnalyzer = new WhereClauseAnalyzer(columnTableResolver);
        //JoinColumnAnalyzer joinColumnAnalyzer = new JoinColumnAnalyzer(columnTableResolver);

        whereClauseAnalyzer.analyze(statement, builder);
        // todo implement me: joinColumnAnalyzer.analyze(statement, builder);

        assertThat(builder.getCriteriaColumns()).anyMatch(column -> column.equalsNormalized("notif_criteria_met.created_on"));
        assertThat(builder.getCriteriaColumns()).anyMatch(column -> column.equalsNormalized("notif_criteria_met.customer_id"));
    }

    @Disabled
    @Test
    void subjoin_join_analyzer() throws JSQLParserException {
        String sql = """
            SELECT `num`.*
            FROM ( `notif_criteria` `n`
                INNER JOIN
                `notif_criteria_met` `num`
                ON `num`.`notif_criteria_id` = `n`.`id`
                    AND `n`.`name` = ?
                     )
                     LEFT JOIN
                 (
                     `notif_criteria` `d`
                         INNER JOIN
                         `notif_criteria_met` `den`
                         ON `den`.`notif_criteria_id` = `d`.`id`
                             AND `d`.`name` = ?
                     )
                 ON `den`.`store_id` = `num`.`store_id`
                     AND `den`.`customer_id` = `num`.`customer_id`
            WHERE `num`.`created_on` >= ?
              AND `den`.`customer_id` IS NULL
            ORDER BY `num`.`created_on`""";

        Statement statement = CCJSqlParserUtil.parse(sql);

        JoinColumnAnalyzer joinColumnAnalyzer = new JoinColumnAnalyzer(columnTableResolver);

        // todo implement me:
        joinColumnAnalyzer.analyze(statement, builder);
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
                        `store`
                    WHERE
                        `id` = `ds` . `store_id` )
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
    void qualified_store_id() throws JSQLParserException {
        String sql = """
                SELECT
                    `fc` . `id` ,
                    `fc` . `store_id` AS `storeId` ,
                    `fc` . `name` AS NAME ,
                    `fc` . `value` AS VALUE ,
                    `cliCre` . `username` AS `createdBy` ,
                    `fc` . `created_on` AS `createdOn` ,
                    `cliDel` . `username` AS `deletedBy` ,
                    `fc` . `deleted_on` AS `deletedOn`
                FROM
                    `store_config` `fc`
                LEFT JOIN
                    TENANT `cliCre`
                        ON `fc` . `created_by` = `cliCre` . `id`
                LEFT JOIN
                    TENANT `cliDel`
                        ON `fc` . `deleted_by` = `cliDel` . `id`
                WHERE
                    `store_id` = ?
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
                    `tenant` . `id` AS `createdBy` ,
                    `CONCAT` ( `tenant` . `first_name` ,
                    ? ,
                    `tenant` . `last_name` ) AS `createdByName` ,
                    `CONCAT` ( `tenant2` . `first_name` ,
                    ? ,
                    `tenant2` . `last_name` ) AS `deletedByName` ,
                    `report_config_field` . `deleted_on` AS `deletedOn` ,
                    `report_config_field` . `deleted_by` AS `deletedBy`
                FROM
                    `report_config_field`
                JOIN
                    `report`
                        ON `report_config_field` . `report_id` = `report` . `id`
                JOIN
                    `tdb` . `tenant` AS TENANT
                        ON `report_config_field` . `created_by` = `tenant` . `id`
                LEFT JOIN
                    `tdb` . `tenant` AS `tenant2`
                        ON `report_config_field` . `deleted_by` = `tenant2` . `id`
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
        assertContainsJoinColumns(joinColumns, "report_config_field.created_by", "tdb.tenant.id");
        assertContainsJoinColumns(joinColumns, "report_config_field.created_by", "tdb.tenant.id");
        assertContainsJoinColumns(joinColumns, "report_config_field.deleted_by", "tdb.tenant.id");
    }

    @Test
    void expression_references_true() throws JSQLParserException {
        String sql = """
            SELECT `nd`.*
            FROM `notif_criteria_destination` `ncd`
                     JOIN `notif_destination` `nd` ON `ncd`.`notif_destination_id` = `nd`.`id`
                     JOIN `notif_criteria` `nc` ON `ncd`.`notif_criteria_id` = `nc`.`id`
            WHERE `ncd`.`notif_criteria_id` = ?
              AND `ncd`.`deleted_on` IS NULL
              AND `nd`.`deleted_on` IS NULL
              AND `nd`.`enabled` = TRUE
              AND `nd`.`enabled_on` <= ?""";

        builder.setSchemaName("tdb");
        sqlAnalysisService.analyzeSql(sql, builder);

        List<ColumnReference> criteriaColumns = builder.getCriteriaColumns();

        // [notif_criteria_destination.notif_criteria_id, notif_criteria_destination.deleted_on, notif_destination.deleted_on, notif_destination.enabled, notif_destination.enabled_on]

        assertContainsColumnReferences(criteriaColumns,
                                       "notif_criteria_destination.notif_criteria_id",
                                       "notif_criteria_destination.deleted_on",
                                       "notif_destination.deleted_on",
                                       "notif_destination.enabled",
                                       "notif_destination.enabled_on");

    }



    @Test
    void table_for_store_id_not_found() throws JSQLParserException {
        String sql = """
                SELECT
                    `fc` . `id` ,
                    `fc` . `store_id` AS `storeId` ,
                    `fc` . `name` AS NAME ,
                    `fc` . `value` AS VALUE ,
                    `cliCre` . `username` AS `createdBy` ,
                    `fc` . `created_on` AS `createdOn` ,
                    `cliDel` . `username` AS `deletedBy` ,
                    `fc` . `deleted_on` AS `deletedOn`
                FROM
                    `store_config` `fc`
                LEFT JOIN
                    TENANT `cliCre`
                        ON `fc` . `created_by` = `cliCre` . `id`
                LEFT JOIN
                    TENANT `cliDel`
                        ON `fc` . `deleted_by` = `cliDel` . `id`
                WHERE
                    `store_id` = ?
                ORDER BY
                    `fc` . `created_on` DESC LIMIT ? \
            """;

        sqlAnalysisService.analyzeSql(sql, builder);

        List<ColumnReference> criteriaColumns = builder.getCriteriaColumns();
        assertContainsColumnReferences(criteriaColumns, "store_config.store_id");
    }

    @Test
    void multiple_tables_one_qualified_one_not() throws JSQLParserException {
        String sql = """
                SELECT
                    `c` . `contributor_id` ,
                    `c` . `content_id` ,
                    MIN ( `c` . `created_on` ) AS `insight_created` ,
                    `c` . `store_id` ,
                    ( SELECT
                        COUNT ( `id` )
                    FROM
                        `analytics` . `contribution`
                    WHERE
                        `content_id` = `c` . `content_id`
                        AND `contributor_id` != `c` . `contributor_id`
                        AND `store_id` != `c` . `store_id` ) AS `updates_at_different_store` ,
                    ( SELECT
                        COUNT ( `id` )
                    FROM
                        `analytics` . `contribution`
                    WHERE
                        `content_id` = `c` . `content_id`
                        AND `contributor_id` != `c` . `contributor_id`
                        AND `store_id` = `c` . `store_id` ) AS `updates_at_same_store` ,
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

    @Test
    void scope_of_in_subselect() {
        String sql = """
                DELETE
                FROM
                    `deleted_criteria_label`
                WHERE
                    `store_id` = ?
                    AND `id` IN (
                        SELECT
                            `label_id`
                        FROM
                            `deleted_criteria_label_map`
                        WHERE
                            `store_id` = ?
                            AND `criteria_id` IN (
                                ...
                            )
                    ) \
            """;

        // todo keep this from throwing multiple tables found for column: store_id
    }


}
