package com.github.safetytrick.sqlexplorer.analysis;

import java.util.Collections;
import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.safetytrick.sqlexplorer.analysis.Assertions.assertContainsJoinColumns;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class JoinColumnAnalyzerTest {
    private SqlAnalysisService sqlAnalysisService;
    private SqlProfile.Builder builder;

    @BeforeEach
    public void setUp() {
        SchemaFixture fixture = SchemaFixture.allFixtures();
        sqlAnalysisService = fixture.service(new JoinColumnAnalyzer(fixture.resolver()));
        builder = SqlProfile.newBuilder();
    }

    @Test
    public void testNoJoins() throws JSQLParserException {
        String sql = """
            SELECT `id` , `report_column_id` AS `reportColumnId` , `report_column_config_field_type` AS `reportColumnConfigFieldType` , \
            `report_column_config_field_option` AS `columnConfigFieldOption` , \
            VALUE , `created_on` AS `createdOn` , `created_by` AS `createdBy` , `deleted_on` AS `deletedOn` , \
            `deleted_by` AS `deletedBy` FROM `report_column_config_field` \
            WHERE `report_column_id` = ? AND `report_column_config_field_type` = ? AND `deleted_on` IS NULL AND `deleted_by` IS NULL\
            """;

        sqlAnalysisService.analyzeSql(sql, builder);
        assertEquals(Collections.emptyList(), builder.getJoinColumns());
    }

    @Test
    public void testSimpleJoin() throws JSQLParserException {
        String sql = "SELECT * FROM branch join branch_config ON branch.id = branch_config.branch_id";
        sqlAnalysisService.analyzeSql(sql, builder);

        assertNotNull(builder.getJoinColumns());
        JoinColumns joinPair = builder.getJoinColumns().getFirst();
        assertEquals(joinPair.getLeft(), ColumnReference.fromString("branch.id"));
        assertEquals(joinPair.getRight(), ColumnReference.fromString("branch_config.branch_id"));
    }

    @Test
    public void testSimpleJoin_with_aliases() throws JSQLParserException {
        String sql = "SELECT * FROM branch f join branch_config fc ON f.id = fc.branch_id";
        sqlAnalysisService.analyzeSql(sql, builder);


        assertNotNull(builder.getJoinColumns());
        assertEquals(1, builder.getJoinColumns().size());
        JoinColumns joinPair = builder.getJoinColumns().getFirst();

        assertEquals(joinPair.getLeft(), ColumnReference.fromString("branch.id"));
        assertEquals(joinPair.getRight(), ColumnReference.fromString("branch_config.branch_id"));
    }

    @Test
    public void testJoinWithOnAnd() throws JSQLParserException {
        String sql = """
                SELECT
                    DISTINCTROW `member` . `id`\s
                FROM
                    `member`\s
                JOIN
                    `current_member_demographics`\s
                        ON `current_member_demographics` . `member_id` = `member` . `id`\s
                JOIN
                    `member_name` `p`\s
                        ON `p` . `member_id` = `member` . `id`\s
                        AND `p` . `id` = `current_member_demographics` . `member_name_id`\s
                WHERE
                    `p` . `member_id` IN (
                        SELECT
                            DISTINCTROW `member_id`\s
                        FROM
                            `member_referral`\s
                        WHERE
                            (
                                `referring_branch_id` IN (
                                    ?
                                )\s
                                OR `managing_branch_id` IN (
                                    ?
                                )\s
                                OR `coordinating_branch_id` IN (
                                    ?
                                )\s
                            )\s
                            AND `managing_branch_status` IN (
                                ...
                            )\s
                    )\s
                    AND (
                        (
                            `p` . `last_name` = ?\s
                            OR `p` . `last_name` = ?\s
                            OR `p` . `last_name` = ?\s
                        )\s
                        AND (
                            `p` . `first_name` = ?\s
                            OR `p` . `first_name` = ?\s
                            OR `p` . `first_name` = ?\s
                        )\s
                    )\s
                    AND `current_member_demographics` . `branch_id` IN (
                        ?
                    ) LIMIT ? \
            """;

        sqlAnalysisService.analyzeSql(sql, builder);


        assertNotNull(builder.getJoinColumns());
        JoinColumns joinPair = builder.getJoinColumns().getFirst();

        assertThat(joinPair.getLeft()).matches(left -> left.equalsNormalized("current_member_demographics.member_id"));
        assertThat(joinPair.getRight()).matches(right -> right.equalsNormalized("member.id"));
        JoinColumns andJoin = builder.getJoinColumns().get(1);

        assertThat(andJoin.getLeft()).matches(left -> left.equalsNormalized("member_name.member_id"));
        assertThat(andJoin.getRight()).matches(right -> right.equalsNormalized("member.id"));

        JoinColumns secondHalf = builder.getJoinColumns().get(2);

        assertThat(secondHalf.getLeft()).matches(left -> left.equalsNormalized("member_name.id"));
        assertThat(secondHalf.getRight()).matches(right -> right.equalsNormalized("current_member_demographics.member_name_id"));
    }

    @Test
    public void testOnAndWhereColumnJoins() throws JSQLParserException {
        String sql = """
                SELECT
                    `member3_` . `id` AS `id81_` ,
                    `member3_` . `created_on` AS `created2_81_`
                FROM
                    `member_name` `membernam0_`
                INNER JOIN
                    `member` `member3_`
                        ON `membernam0_` . `member_id` = `member3_` . `id` CROSS
                JOIN
                    `member_ident` `memberide1_` CROSS
                JOIN
                    `member_address` `memberadd2_`
                WHERE
                    (
                        `membernam0_` . `first_name` LIKE ?
                        OR `membernam0_` . `first_name` = ?
                    )
                    AND (
                        `membernam0_` . `last_name` LIKE ?
                        OR `membernam0_` . `last_name` = ?
                    )
                    AND `memberide1_` . `date_of_signup` = ?
                    AND (
                        (
                            `memberadd2_` . `street_address` LIKE ?
                        )
                        AND `length` (
                            ?
                        ) - ? >= ?
                        OR (
                            ? LIKE `concat` (
                                ? , `memberadd2_` . `street_address` , ?
                            )
                        )
                        AND `length` (
                            `memberadd2_` . `street_address`
                        ) >= ?
                    )
                    AND `memberide1_` . `member_id` = `membernam0_` . `member_id`
                    AND `memberide1_` . `member_id` = `memberadd2_` . `member_id`
                GROUP BY
                    `membernam0_` . `member_id`
                """;

        sqlAnalysisService = new SqlAnalysisService(new JoinColumnAnalyzer(), new WhereClauseAnalyzer());
        sqlAnalysisService.analyzeSql(sql, builder);


        assertNotNull(builder.getJoinColumns());
        JoinColumns joinPair = builder.getJoinColumns().getFirst();

        assertThat(joinPair.getLeft()).matches(left -> left.equalsNormalized("member_name.member_id"));
        assertThat(joinPair.getRight()).matches(right -> right.equalsNormalized("member.id"));

        JoinColumns andJoin = builder.getJoinColumns().get(1);

        assertThat(andJoin.getLeft()).matches(left -> left.equalsNormalized("member_ident.member_id"));
        assertThat(andJoin.getRight()).matches(right -> right.equalsNormalized("member_name.member_id"));

        JoinColumns secondHalf = builder.getJoinColumns().get(2);

        assertThat(secondHalf.getLeft()).matches(left -> left.equalsNormalized("member_ident.member_id"));
        assertThat(secondHalf.getRight()).matches(right -> right.equalsNormalized("member_address.member_id"));

    }

    @Test
    public void testSubSelect() throws JSQLParserException {
        String sql = """
            SELECT
                    temp . *
                FROM
                    member_session mother_session
                JOIN
                    (
                        SELECT
                            pv . * ,
                            ins . member_id AS mother
                        FROM
                            member_plan ins ,
                            member_plan ins2 ,
                            member_ident ident ,
                            member_session_plan pvi ,
                            member_session pv
                        WHERE
                            pv . id = pvi . member_session_id
                            AND pv . checkout_date >= ?
                            AND pv . checkout_date <= ?
                            AND (
                                pv . session_type = ?
                                OR pv . main_class IN (
                                    ?
                                )
                            )
                            AND ident . date_of_signup > DATE_SUB ( NOW ( ) , INTERVAL ? SQL_TSI_DAY )
                            AND ins2 . member_id = ident . member_id
                            AND ins . branch_id = ?
                            AND ins2 . account_number = ins . account_number
                            AND pvi . member_plan_id = ins2 . id
                            AND (
                                DATE ( pv . checkin_date ) <= ins . service_end
                                OR ins . service_end IS NULL
                            )
                            AND (
                                DATE ( pv . checkin_date ) >= ins . service_start
                                OR ins . service_start IS NULL
                            )
                            AND ins2 . branch_id IS NULL
                        GROUP BY
                            pv . id
                    ) AS temp
                        ON temp . mother = mother_session . member_id
                WHERE
                    mother_session . member_id = temp
            """;

        builder.setSchemaName("tdb");
        sqlAnalysisService.analyzeSql(sql, builder);

        List<JoinColumns> joinColumns = builder.getJoinColumns();

        assertContainsJoinColumns(joinColumns, "member_plan.member_id", "member_session.member_id");

    }

    @Test
    public void testAndWithinOnClause() throws JSQLParserException {
        String sql = """
                SELECT
                    `pvd` . `branch_id`
                FROM
                    `member_session` `pvd`
                LEFT JOIN
                    `branch` `f`
                        ON `f` . `id` = `pvd` . `branch_id`
                LEFT JOIN
                    `branch_config` `fc`
                        ON (
                            `f` . `id` = `fc` . `id`
                            AND `fc` . `name` = ?
                        )
                WHERE
                    `pvd` . `member_id` = ?
                    AND `pvd` . `checkin_date` >= SUBDATE ( NOW ( ) , `IFNULL` ( `fc` . `value` , ? ) )
                    AND `f` . `type` IN (
                        ...
                    )
                GROUP BY
                    `pvd` . `branch_id`
            """;

        builder.setSchemaName("tdb");

        sqlAnalysisService.analyzeSql(sql, builder);

        List<JoinColumns> joinColumns = builder.getJoinColumns();
        assertContainsJoinColumns(joinColumns, "branch.id", "branch_config.id");
    }

    @Test
    public void testJoinWithMismatchedCaseAndQuoting() throws JSQLParserException {
        String sql = """
                SELECT
                    `username` ,
                    `last_branch_id` AS `branchId` ,
                    `org_password` . `salted_password` AS `saltedPassword` ,
                    `org` . `id` AS `userId`
                FROM
                    ORG
                JOIN
                    `org_password`
                        ON `org` . `id` = `org_password` . `org_id`
                WHERE
                    `username` = ?
                    AND NOT `org` . `is_blocked`
                    AND NOT `org` . `is_deleted`
                ORDER BY
                    `org_password` . `created_on` DESC LIMIT ?
            """;

        builder.setSchemaName("tdb");
        sqlAnalysisService.analyzeSql(sql, builder);

        List<JoinColumns> joinColumns = builder.getJoinColumns();

        assertContainsJoinColumns(joinColumns, "ORG.id", "org_password.org_id");
    }

    @Test
    void truncated_before_field_expression() throws JSQLParserException {
        String sql = """
            SELECT
                    ? AS `relationship_type` ,
                    `agent` . `branch_id` AS `relation_branch_id` ,
                    `f` . `name` AS `relation_branch_name` ,
                    `pi` . `member_id` ,
                    `agent` . `identifier` AS `member_identifier` ,
                    `pi` . `type` ,
                    `agent` . `effective_on` ,
                    `deleted_on` ,
                    `agent` . `created_on`
                FROM
                    `tdb` . `deleted_member_branch_relationship` `agent`
                INNER JOIN
                    `member_identifier` `pi`
                        ON `pi` . `identifier` = `agent` . `identifier`
                        AND `pi` . `branch_id` = `agent`""";

        builder.setSchemaName("tdb");

        sqlAnalysisService.analyzeSql(sql, builder);


        List<JoinColumns> joinColumns = builder.getJoinColumns();

        assertContainsJoinColumns(joinColumns, "member_identifier.identifier", "tdb.deleted_member_branch_relationship.identifier");
    }

    @Test
    void join_from_in_subselect() throws JSQLParserException {
        String sql = """
                SELECT
                    `branch_id` ,
                    COUNT ( `member_id` )
                FROM
                    `member_branch_relationship`
                WHERE
                    `member_id` = ?
                    AND `branch_id` IN (
                        SELECT
                            `branch_id`
                        FROM
                            `branch_config`
                        WHERE
                            NAME = ?
                            AND VALUE = ?
                            AND `deleted_by` IS NULL
                    )
                GROUP BY
                    `branch_id`
                """;

        sqlAnalysisService.analyzeSql(sql, builder);


        List<JoinColumns> joinColumns = builder.getJoinColumns();
        assertContainsJoinColumns(joinColumns, "member_branch_relationship.branch_id", "branch_config.branch_id");
    }

    @Test
    public void capturesJoinTypes() throws JSQLParserException {
        String sql = """
            SELECT s.id FROM sessions s \
            JOIN users u ON s.user_id = u.id \
            LEFT JOIN accounts a ON s.account_id = a.id\
            """;

        sqlAnalysisService.analyzeSql(sql, builder);

        List<JoinColumns> joinColumns = builder.getJoinColumns();
        assertThat(joinColumns).extracting(JoinColumns::getJoinType)
            .containsExactly("INNER", "LEFT");
    }
}
