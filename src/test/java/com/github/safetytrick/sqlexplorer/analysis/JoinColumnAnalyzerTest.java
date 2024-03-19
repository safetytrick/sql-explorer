package com.github.safetytrick.sqlexplorer.analysis;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.github.safetytrick.sqlexplorer.analysis.parser.SqlParser;
import com.github.safetytrick.sqlexplorer.analysis.resolver.ColumnTableResolver;
import com.github.safetytrick.sqlexplorer.model.ColumnReference;
import com.github.safetytrick.sqlexplorer.model.JoinColumns;
import com.github.safetytrick.sqlexplorer.model.SqlProfile;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.safetytrick.sqlexplorer.analysis.Assertions.assertContainsJoinColumns;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class JoinColumnAnalyzerTest {
    private SqlAnalysisService sqlAnalysisService;
    private SqlProfile.Builder builder;
    private ColumnTableResolver columnTableResolver;

    @BeforeEach
    public void setUp() {
        columnTableResolver = mock(ColumnTableResolver.class);
        sqlAnalysisService = new SqlAnalysisService(new JoinColumnAnalyzer(columnTableResolver));
        builder = SqlProfile.builder();
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
        assertEquals(Collections.emptyList(), builder.view().getJoinColumns());
    }

    @Test
    public void testSimpleJoin() throws JSQLParserException {
        String sql = "SELECT * FROM store join store_config ON store.id = store_config.store_id";
        sqlAnalysisService.analyzeSql(sql, builder);

        assertNotNull(builder.view().getJoinColumns());
        JoinColumns joinPair = builder.view().getJoinColumns().get(0);
        assertEquals(joinPair.left(), ColumnReference.fromString("store.id"));
        assertEquals(joinPair.right(), ColumnReference.fromString("store_config.store_id"));
    }

    @Test
    public void testSimpleJoin_with_aliases() throws JSQLParserException {
        String sql = "SELECT * FROM store f join store_config fc ON f.id = fc.store_id";
        sqlAnalysisService.analyzeSql(sql, builder);


        assertNotNull(builder.view().getJoinColumns());
        assertEquals(1, builder.view().getJoinColumns().size());
        JoinColumns joinPair = builder.view().getJoinColumns().get(0);

        assertEquals(joinPair.left(), ColumnReference.fromString("store.id"));
        assertEquals(joinPair.right(), ColumnReference.fromString("store_config.store_id"));
    }

    @Test
    public void testJoinWithOnAnd() throws JSQLParserException {
        String sql = """
                SELECT
                    DISTINCTROW `customer` . `id`\s
                FROM
                    `customer`\s
                JOIN
                    `current_customer_demographics`\s
                        ON `current_customer_demographics` . `customer_id` = `customer` . `id`\s
                JOIN
                    `customer_name` `p`\s
                        ON `p` . `customer_id` = `customer` . `id`\s
                        AND `p` . `id` = `current_customer_demographics` . `customer_name_id`\s
                WHERE
                    `p` . `customer_id` IN (
                        SELECT
                            DISTINCTROW `customer_id`\s
                        FROM
                            `customer_referral`\s
                        WHERE
                            (
                                `referring_store_id` IN (
                                    ?
                                )\s
                                OR `managing_store_id` IN (
                                    ?
                                )\s
                                OR `coordinating_store_id` IN (
                                    ?
                                )\s
                            )\s
                            AND `managing_store_status` IN (
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
                    AND `current_customer_demographics` . `store_id` IN (
                        ?
                    ) LIMIT ? \
            """;

        sqlAnalysisService.analyzeSql(sql, builder);


        assertNotNull(builder.view().getJoinColumns());
        JoinColumns joinPair = builder.view().getJoinColumns().get(0);
        assertEquals(joinPair.left(), ColumnReference.fromString("current_customer_demographics.customer_id"));
        assertEquals(joinPair.right(), ColumnReference.fromString("customer.id"));
        JoinColumns andJoin = builder.view().getJoinColumns().get(1);

        assertEquals(andJoin.left(), ColumnReference.fromString("customer_name.customer_id"));
        assertEquals(andJoin.right(), ColumnReference.fromString("customer.id"));

        JoinColumns secondHalf = builder.view().getJoinColumns().get(2);
        assertEquals(secondHalf.left(), ColumnReference.fromString("customer_name.id"));
        assertEquals(secondHalf.right(), ColumnReference.fromString("current_customer_demographics.customer_name_id"));
    }

    @Test
    public void testOnAndWhereColumnJoins() throws JSQLParserException {
        String sql = """
                SELECT
                    `customer3_` . `id` AS `id81_` ,
                    `customer3_` . `created_on` AS `created2_81_`
                FROM
                    `customer_name` `customernam0_`
                INNER JOIN
                    `customer` `customer3_`
                        ON `customernam0_` . `customer_id` = `customer3_` . `id` CROSS
                JOIN
                    `customer_ident` `customeride1_` CROSS
                JOIN
                    `customer_address` `customeradd2_`
                WHERE
                    (
                        `customernam0_` . `first_name` LIKE ?
                        OR `customernam0_` . `first_name` = ?
                    )
                    AND (
                        `customernam0_` . `last_name` LIKE ?
                        OR `customernam0_` . `last_name` = ?
                    )
                    AND `customeride1_` . `date_of_birth` = ?
                    AND (
                        (
                            `customeradd2_` . `street_address` LIKE ?
                        )
                        AND `length` (
                            ?
                        ) - ? >= ?
                        OR (
                            ? LIKE `concat` (
                                ? , `customeradd2_` . `street_address` , ?
                            )
                        )
                        AND `length` (
                            `customeradd2_` . `street_address`
                        ) >= ?
                    )
                    AND `customeride1_` . `customer_id` = `customernam0_` . `customer_id`
                    AND `customeride1_` . `customer_id` = `customeradd2_` . `customer_id`
                GROUP BY
                    `customernam0_` . `customer_id`
                """;

        sqlAnalysisService = new SqlAnalysisService(Arrays.asList(new JoinColumnAnalyzer(), new WhereClauseAnalyzer()), new SqlParser());
        sqlAnalysisService.analyzeSql(sql, builder);


        assertNotNull(builder.view().getJoinColumns());
        JoinColumns joinPair = builder.view().getJoinColumns().get(0);

        assertEquals(joinPair.left(), ColumnReference.fromString("customer_name.customer_id"));
        assertEquals(joinPair.right(), ColumnReference.fromString("customer.id"));

        JoinColumns andJoin = builder.view().getJoinColumns().get(1);

        assertEquals(andJoin.left(), ColumnReference.fromString("customer_ident.customer_id"));
        assertEquals(andJoin.right(), ColumnReference.fromString("customer_name.customer_id"));

        JoinColumns secondHalf = builder.view().getJoinColumns().get(2);

        assertEquals(secondHalf.left(), ColumnReference.fromString("customer_ident.customer_id"));
        assertEquals(secondHalf.right(), ColumnReference.fromString("customer_address.customer_id"));
    }

    @Test
    public void testSubSelect() throws JSQLParserException {
        String sql = """
            SELECT
                    temp . *
                FROM
                    customer_visit mother_visit
                JOIN
                    (
                        SELECT
                            pv . * ,
                            ins . customer_id AS mother
                        FROM
                            customer_membership ins ,
                            customer_membership ins2 ,
                            customer_ident ident ,
                            customer_visit_membership pvi ,
                            customer_visit pv
                        WHERE
                            pv . id = pvi . customer_visit_id
                            AND pv . checkout_date >= ?
                            AND pv . checkout_date <= ?
                            AND (
                                pv . visit_type = ?
                                OR pv . major_class IN (
                                    ?
                                )
                            )
                            AND ident . date_of_birth > DATE_SUB ( NOW ( ) , INTERVAL ? SQL_TSI_DAY )
                            AND ins2 . customer_id = ident . customer_id
                            AND ins . store_id = ?
                            AND ins2 . account_number = ins . account_number
                            AND pvi . customer_membership_id = ins2 . id
                            AND (
                                DATE ( pv . checkin_date ) <= ins . service_end
                                OR ins . service_end IS NULL
                            )
                            AND (
                                DATE ( pv . checkin_date ) >= ins . service_start
                                OR ins . service_start IS NULL
                            )
                            AND ins2 . store_id IS NULL
                        GROUP BY
                            pv . id
                    ) AS temp
                        ON temp . mother = mother_visit . customer_id
                WHERE
                    mother_visit . customer_id = temp
            """;

        builder.schema("tdb");
        when(columnTableResolver.getAllColumnNamesForTable("tdb", "customer_visit"))
            .thenReturn(Arrays.asList("checkout_date", "checkin_date", "id", "customer_id"));
        sqlAnalysisService.analyzeSql(sql, builder);

        verify(columnTableResolver, atLeastOnce())
            .getAllColumnNamesForTable("tdb", "customer_visit");


        List<JoinColumns> joinColumns = builder.view().getJoinColumns();

        assertContainsJoinColumns(joinColumns, "customer_membership.customer_id", "customer_visit.customer_id");

    }

    @Test
    public void testAndWithinOnClause() throws JSQLParserException {
        String sql = """
                SELECT
                    `pvd` . `store_id`
                FROM
                    `customer_visit` `pvd`
                LEFT JOIN
                    `store` `f`
                        ON `f` . `id` = `pvd` . `store_id`
                LEFT JOIN
                    `store_config` `fc`
                        ON (
                            `f` . `id` = `fc` . `id`
                            AND `fc` . `name` = ?
                        )
                WHERE
                    `pvd` . `customer_id` = ?
                    AND `pvd` . `checkin_date` >= SUBDATE ( NOW ( ) , `IFNULL` ( `fc` . `value` , ? ) )
                    AND `f` . `type` IN (
                        ...
                    )
                GROUP BY
                    `pvd` . `store_id`
            """;

        builder.schema("tdb");

        sqlAnalysisService.analyzeSql(sql, builder);

        List<JoinColumns> joinColumns = builder.view().getJoinColumns();
        assertContainsJoinColumns(joinColumns, "store.id", "store_config.id");
    }

    @Test
    public void testJoinWithMismatchedCaseAndQuoting() throws JSQLParserException {
        String sql = """
                SELECT
                    `username` ,
                    `last_store_id` AS `storeId` ,
                    `client_password` . `salted_password` AS `saltedPassword` ,
                    `client` . `id` AS `userId`
                FROM
                    CLIENT
                JOIN
                    `client_password`
                        ON `client` . `id` = `client_password` . `client_id`
                WHERE
                    `username` = ?
                    AND NOT `client` . `is_blocked`
                    AND NOT `client` . `is_deleted`
                ORDER BY
                    `client_password` . `created_on` DESC LIMIT ?
            """;

        builder.schema("tdb");
        sqlAnalysisService.analyzeSql(sql, builder);

        List<JoinColumns> joinColumns = builder.view().getJoinColumns();

        assertContainsJoinColumns(joinColumns, "CLIENT.id", "client_password.client_id");
    }

    @Test
    void truncated_before_field_expression() throws JSQLParserException {
        String sql = """
            SELECT
                    ? AS `relationship_type` ,
                    `pfr` . `store_id` AS `relation_store_id` ,
                    `f` . `name` AS `relation_store_name` ,
                    `pi` . `customer_id` ,
                    `pfr` . `identifier` AS `customer_identifier` ,
                    `pi` . `type` ,
                    `pfr` . `effective_on` ,
                    `deleted_on` ,
                    `pfr` . `created_on`
                FROM
                    `tdb` . `deleted_customer_store_relationship` `pfr`
                INNER JOIN
                    `customer_identifier` `pi`
                        ON `pi` . `identifier` = `pfr` . `identifier`
                        AND `pi` . `store_id` = `pfr`""";

        builder.schema("tdb");


        when(columnTableResolver.selectTableNameFromCandidates(Arrays.asList("tdb.deleted_customer_store_relationship",
                                                                             "customer_identifier"), "pfr")).thenReturn(null);

        sqlAnalysisService.analyzeSql(sql, builder);


        List<JoinColumns> joinColumns = builder.view().getJoinColumns();

        assertContainsJoinColumns(joinColumns, "customer_identifier.identifier", "tdb.deleted_customer_store_relationship.identifier");
    }

    @Test
    void join_from_in_subselect() throws JSQLParserException {
        String sql = """
                SELECT
                    `store_id` ,
                    COUNT ( `customer_id` )
                FROM
                    `customer_store_relationship`
                WHERE
                    `customer_id` = ?
                    AND `store_id` IN (
                        SELECT
                            `store_id`
                        FROM
                            `store_config`
                        WHERE
                            NAME = ?
                            AND VALUE = ?
                            AND `deleted_by` IS NULL
                    )
                GROUP BY
                    `store_id`
                """;

        when(columnTableResolver.selectTableNameFromCandidates(Collections.singletonList("store_config"), "store_id"))
            .thenReturn("store_config");

        sqlAnalysisService.analyzeSql(sql, builder);


        List<JoinColumns> joinColumns = builder.view().getJoinColumns();
        assertContainsJoinColumns(joinColumns, "customer_store_relationship.store_id", "store_config.store_id");
    }
}
