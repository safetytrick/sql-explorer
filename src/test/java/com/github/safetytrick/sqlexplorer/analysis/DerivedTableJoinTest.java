package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A join to a derived table resolves each column through the derived table's
 * select list to the table it came from (sqlx-9xg).
 *
 * <p>A name the select list spelled differently from the ON clause — bare
 * {@code NAME} against {@code `r_global`.`name`} — missed the lookup, and the
 * miss fell back to the bare column name, which was then read as a table:
 * the join was recorded against tables called {@code name} and
 * {@code description}.
 */
class DerivedTableJoinTest {

    private SchemaFixture fixture;

    @BeforeEach
    void setUp() throws JSQLParserException {
        fixture = SchemaFixture.fromSql("""
            create table account (id bigint not null, name varchar(64), primary key (id));
            create table role (id bigint not null, account_id bigint, name varchar(64), description varchar(255),
                               created datetime, updated datetime, primary key (id));
            """);
    }

    private static List<String> joins(SqlProfile profile) {
        return profile.getJoinColumns().stream().map(DerivedTableJoinTest::pair).toList();
    }

    /** Order-free: a join of a to b is a join of b to a. */
    private static String pair(JoinColumns join) {
        String left = name(join.getLeft());
        String right = name(join.getRight());
        return left.compareTo(right) <= 0 ? left + " = " + right : right + " = " + left;
    }

    private static String name(ColumnReference column) {
        return column.getTable().getNormalizedTableName() + "." + column.getNormalizedColumn();
    }

    @Test
    void aDerivedTablesColumnsResolveToItsSourceTableWhateverTheirSpelling() throws JSQLParserException {
        SqlProfile profile = fixture.analyze("""
            SELECT `a`.`name`, `r`.`id`, `r_global`.`id`
            FROM `role` `r`
            JOIN `account` `a` ON `a`.`id` = `r`.`account_id`
            JOIN (SELECT `id`, NAME, DESCRIPTION FROM `role` WHERE `account_id` IS NULL) AS `r_global`
              ON `r_global`.`name` = `r`.`name` AND `r_global`.`description` = `r`.`description`
            WHERE `r`.`account_id` IS NOT NULL
            """);

        assertThat(joins(profile)).containsExactlyInAnyOrder(
            "account.id = role.account_id",
            "role.name = role.name",
            "role.description = role.description");
    }

    /** The same derived-table column in the select list resolves to its source table, not dual (sqlx-03r). */
    @Test
    void aDerivedTablesColumnInTheSelectListResolvesToItsSourceTable() throws JSQLParserException {
        SqlProfile profile = fixture.analyze("""
            SELECT `a`.`name`, `r`.`id`, `r_global`.`id`, `r_global`.`name`
            FROM `role` `r`
            JOIN `account` `a` ON `a`.`id` = `r`.`account_id`
            JOIN (SELECT `id`, NAME FROM `role` WHERE `account_id` IS NULL) AS `r_global`
              ON `r_global`.`name` = `r`.`name`
            """);

        assertThat(profile.getSelectColumns()).extracting(DerivedTableJoinTest::name)
            .containsExactlyInAnyOrder("account.name", "role.id", "role.name");
    }

    /** A derived table in FROM, with no join at all, resolves the same way. */
    @Test
    void aDerivedTableInFromResolvesItsSelectListColumns() throws JSQLParserException {
        SqlProfile profile = fixture.analyze("""
            SELECT `g`.`name`, COUNT(`g`.`id`) FROM (SELECT `id`, `name` FROM `role` WHERE `account_id` IS NULL) `g`
            GROUP BY `g`.`name`
            """);

        assertThat(profile.getSelectColumns()).extracting(DerivedTableJoinTest::name)
            .containsExactlyInAnyOrder("role.name", "role.id");
        assertThat(profile.getGroupByColumns()).extracting(DerivedTableJoinTest::name)
            .containsExactly("role.name");
    }

    /** A UNION's columns are named by its first branch; reading one no longer throws. */
    @Test
    void aUnionDerivedTableResolvesThroughItsFirstBranch() throws JSQLParserException {
        SqlProfile profile = fixture.analyze("""
            SELECT `u`.`name` FROM `role` `r`
            JOIN (SELECT `id`, `name` FROM `role` UNION SELECT `id`, `name` FROM `account`) `u` ON `u`.`id` = `r`.`id`
            """);

        assertThat(profile.getAnalysisFailureReason()).isNull();
        assertThat(profile.getSelectColumns()).extracting(DerivedTableJoinTest::name)
            .containsExactly("role.name");
        assertThat(joins(profile)).containsExactly("role.id = role.id");
    }

    /** {@code tdb} is {@link TestSchemas#DEFAULT_SCHEMA}. */
    @Test
    void aSchemaQualifiedDerivedTableResolvesToTheTableNotTheSchema() throws JSQLParserException {
        SqlProfile profile = fixture.analyze("""
            SELECT `r`.`id` FROM `tdb`.`role` `r`
            JOIN (SELECT `id`, `name` FROM `tdb`.`role` WHERE `account_id` IS NULL) AS `r_global`
              ON `r_global`.`name` = `r`.`name`
            """);

        assertThat(joins(profile)).containsExactly("role.name = role.name");
    }

    @Test
    void aColumnTheDerivedTableDoesNotProjectIsNotNamedAfterItself() throws JSQLParserException {
        SqlProfile profile = fixture.analyze("""
            SELECT `r`.`id` FROM `role` `r`
            JOIN (SELECT `id` FROM `role` WHERE `account_id` IS NULL) AS `r_global`
              ON `r_global`.`name` = `r`.`name`
            """);

        assertThat(profile.getJoinColumns()).extracting(j -> j.getLeft().getTable().getNormalizedTableName())
            .doesNotContain("name");
        assertThat(profile.getJoinColumns()).extracting(j -> j.getRight().getTable().getNormalizedTableName())
            .doesNotContain("name");
    }
}
