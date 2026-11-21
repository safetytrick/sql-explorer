package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelatedSubqueryTest {

    private SchemaFixture fixture;

    @BeforeEach
    void setUp() throws JSQLParserException {
        fixture = SchemaFixture.fromSql("""
            create table account (id bigint not null, status varchar(16), owner_id bigint, primary key (id));
            create table account_group (id bigint not null, account_id bigint, group_id bigint, primary key (id));
            create table account_site (id bigint not null, account_id bigint, site_id bigint, primary key (id));
            create table site (id bigint not null, group_id bigint, primary key (id));
            """);
    }

    private static final String COUNT_WITH_CORRELATED_EXISTS = """
        SELECT COUNT(`a0_`.`id`) FROM `account` `a0_`
        WHERE (`a0_`.`status` IN (?)
          AND (EXISTS (SELECT ? FROM `account_group` `ag1_`
                       WHERE `ag1_`.`account_id` = `a0_`.`id` AND `ag1_`.`group_id` IN (?))
               OR EXISTS (SELECT ? FROM `account_site` `as1_`
                          JOIN `site` `s2_` ON `as1_`.`site_id` = `s2_`.`id`
                          WHERE `as1_`.`account_id` = `a0_`.`id` AND `s2_`.`group_id` IN (?))) = ?)
          AND ((`a0_`.`owner_id` = ?))
        """;

    private static List<String> joins(SqlProfile profile) {
        return profile.getJoinColumns().stream().map(CorrelatedSubqueryTest::pair).toList();
    }

    private static String pair(JoinColumns join) {
        String left = name(join.getLeft());
        String right = name(join.getRight());
        return left.compareTo(right) <= 0 ? left + " = " + right : right + " = " + left;
    }

    private static String name(ColumnReference column) {
        return column.getTable().getNormalizedTableName() + "." + column.getNormalizedColumn();
    }

    @Test
    void eachCorrelationJoinsTheInnerTableToTheOuterOne() throws JSQLParserException {
        SqlProfile profile = fixture.analyze(COUNT_WITH_CORRELATED_EXISTS);

        assertThat(joins(profile)).containsExactlyInAnyOrder(
            "account.id = account_group.account_id",
            "account.id = account_site.account_id",
            "account_site.site_id = site.id");
    }

    @Test
    void noOuterColumnIsAttributedToAnInnerTableOrToDual() throws JSQLParserException {
        SqlProfile profile = fixture.analyze(COUNT_WITH_CORRELATED_EXISTS);

        assertThat(profile.getCriteriaColumns()).extracting(CorrelatedSubqueryTest::name)
            .doesNotContain("account_group.id", "account_site.id", "dual.id")
            .contains("account.id", "account.status", "account.owner_id", "account_group.group_id", "site.group_id");
    }

    @Test
    void anInSubqueryContributesItsOwnJoin() throws JSQLParserException {
        SqlProfile profile = fixture.analyze("""
            SELECT `a0_`.`id` FROM `account` `a0_`
            WHERE `a0_`.`id` IN (SELECT `as1_`.`account_id` FROM `account_site` `as1_`
                                 JOIN `site` `s2_` ON `as1_`.`site_id` = `s2_`.`id`
                                 WHERE `s2_`.`group_id` = ?)
            """);

        assertThat(joins(profile)).contains("account_site.site_id = site.id");
    }

    @Test
    void anUnqualifiedColumnInASingleTableSubqueryIsThatTables() throws JSQLParserException {
        SqlProfile profile = fixture.analyze("""
            SELECT `a0_`.`id` FROM `account` `a0_`
            WHERE EXISTS (SELECT ? FROM `account_group` WHERE `group_id` = ? AND `account_id` = `a0_`.`id`)
            """);

        assertThat(profile.getCriteriaColumns()).extracting(CorrelatedSubqueryTest::name)
            .contains("account_group.group_id", "account_group.account_id", "account.id");
        assertThat(joins(profile)).containsExactly("account.id = account_group.account_id");
    }
}
