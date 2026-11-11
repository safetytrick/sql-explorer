package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ORDER BY columns are a role, resolved through table and select-list aliases (sqlx-n5j.8). */
class OrderByColumnsTest {

    private SchemaFixture fixture;

    @BeforeEach
    void setUp() throws JSQLParserException {
        fixture = SchemaFixture.fromSql("""
            create table account (id bigint not null, name varchar(64), created_at datetime, primary key (id));
            create table site (id bigint not null, account_id bigint, label varchar(64), rank_order int,
                               primary key (id));
            """);
    }

    private static List<String> orderBy(SqlProfile profile) {
        return names(profile.getOrderByColumns());
    }

    private static List<String> names(List<ColumnReference> columns) {
        return columns.stream().map(ColumnReference::toStringNormalized).toList();
    }

    @Test
    void aStatementWithoutOrderByHasNone() throws JSQLParserException {
        assertThat(fixture.analyze("SELECT id FROM account WHERE name = ?").getOrderByColumns()).isEmpty();
    }

    @Test
    void tableAliasesResolveToTheirTables() throws JSQLParserException {
        SqlProfile profile = fixture.analyze("""
            SELECT a.id, s.label FROM account a JOIN site s ON s.account_id = a.id
            ORDER BY s.rank_order DESC, a.created_at
            """);

        assertThat(orderBy(profile)).containsExactly("tdb.site.rank_order", "tdb.account.created_at");
    }

    @Test
    void anUnqualifiedColumnResolvesAgainstTheSchema() throws JSQLParserException {
        SqlProfile profile = fixture.analyze("""
            SELECT a.id FROM account a JOIN site s ON s.account_id = a.id ORDER BY rank_order
            """);

        assertThat(orderBy(profile)).containsExactly("tdb.site.rank_order");
    }

    @Test
    void aSelectListAliasStandsForItsExpression() throws JSQLParserException {
        // `name` is also a column of account: MySQL takes the alias first, and so does this
        SqlProfile profile = fixture.analyze("""
            SELECT a.id, s.label AS name FROM account a JOIN site s ON s.account_id = a.id ORDER BY name
            """);

        assertThat(orderBy(profile)).containsExactly("tdb.site.label");
    }

    @Test
    void anAggregateAliasRecordsTheColumnsItReads() throws JSQLParserException {
        SqlProfile profile = fixture.analyze("""
            SELECT s.account_id, MAX(s.rank_order) AS top FROM site s GROUP BY s.account_id ORDER BY top
            """);

        assertThat(orderBy(profile)).containsExactly("tdb.site.rank_order");
        assertThat(names(profile.getGroupByColumns())).containsExactly("tdb.site.account_id");
    }

    @Test
    void aPositionStandsForThatSelectItem() throws JSQLParserException {
        SqlProfile profile = fixture.analyze("SELECT a.id, a.created_at FROM account a ORDER BY 2");

        assertThat(orderBy(profile)).containsExactly("tdb.account.created_at");
    }

    @Test
    void aUnionsOrderByNamesTheFirstBranchsColumns() throws JSQLParserException {
        SqlProfile profile = fixture.analyze("""
            SELECT a.name FROM account a UNION SELECT s.label FROM site s ORDER BY name
            """);

        assertThat(orderBy(profile)).containsExactly("tdb.account.name");
    }

    @Test
    void updateAndDeleteOrderByAreRecorded() throws JSQLParserException {
        assertThat(orderBy(fixture.analyze(
            "UPDATE site SET label = ? WHERE account_id = ? ORDER BY rank_order LIMIT 1")))
            .containsExactly("tdb.site.rank_order");
        assertThat(orderBy(fixture.analyze("DELETE FROM account WHERE name = ? ORDER BY created_at LIMIT 10")))
            .containsExactly("tdb.account.created_at");
    }

    @Test
    void orderByIsNotCriteria() throws JSQLParserException {
        SqlProfile profile = fixture.analyze("SELECT id FROM account WHERE name = ? ORDER BY created_at");

        assertThat(names(profile.getCriteriaColumns())).containsExactly("tdb.account.name");
        assertThat(orderBy(profile)).containsExactly("tdb.account.created_at");
    }
}
