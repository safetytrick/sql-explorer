package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.CriteriaComparison;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CriteriaComparisonTest {

    private SchemaFixture fixture;

    @BeforeEach
    void setUp() throws JSQLParserException {
        fixture = SchemaFixture.fromSql("""
            create table account (id bigint not null, name varchar(64), status varchar(16),
                                  created_at datetime, deleted_at datetime, primary key (id));
            create table site (id bigint not null, account_id bigint, label varchar(64), prefix varchar(8),
                               primary key (id));
            """);
    }

    private static List<String> comparisons(SqlProfile profile) {
        return profile.getCriteriaComparisons().stream().map(CriteriaComparisonTest::describe).toList();
    }

    private static String describe(CriteriaComparison comparison) {
        String described = comparison.getColumn().toStringNormalized() + " " + comparison.getKind();
        return comparison.getLeadingWildcard() == null ? described
            : described + " leading=" + comparison.getLeadingWildcard();
    }

    private static List<String> criteria(SqlProfile profile) {
        return profile.getCriteriaColumns().stream().map(ColumnReference::toStringNormalized).toList();
    }

    @Test
    void aParameterPatternIsLikeWithAnUnknownWildcard() throws JSQLParserException {
        SqlProfile profile = fixture.analyze("SELECT id FROM account WHERE name LIKE ?");

        assertThat(comparisons(profile)).containsExactly("tdb.account.name like");
        assertThat(criteria(profile)).containsExactly("tdb.account.name");
    }

    @Test
    void aLiteralPatternSaysWhetherItLeadsWithAWildcard() throws JSQLParserException {
        assertThat(comparisons(fixture.analyze("SELECT id FROM account WHERE name LIKE '%smith'")))
            .containsExactly("tdb.account.name like leading=true");
        assertThat(comparisons(fixture.analyze("SELECT id FROM account WHERE name LIKE '_mith'")))
            .containsExactly("tdb.account.name like leading=true");
        assertThat(comparisons(fixture.analyze("SELECT id FROM account WHERE name LIKE 'smi%'")))
            .containsExactly("tdb.account.name like leading=false");
    }

    @Test
    void notLikeIsItsOwnKind() throws JSQLParserException {
        SqlProfile profile = fixture.analyze("SELECT id FROM account WHERE status NOT LIKE '%closed%'");

        assertThat(comparisons(profile)).containsExactly("tdb.account.status not like leading=true");
    }

    @Test
    void concatWithALiteralFirstArgumentIsRead() throws JSQLParserException {
        assertThat(comparisons(fixture.analyze("SELECT id FROM account WHERE name LIKE CONCAT('%', ?)")))
            .containsExactly("tdb.account.name like leading=true");
        assertThat(comparisons(fixture.analyze("SELECT id FROM account WHERE name LIKE CONCAT(?, '%')")))
            .containsExactly("tdb.account.name like");
    }

    @Test
    void aWrappedColumnIsStillMatched() throws JSQLParserException {
        SqlProfile profile = fixture.analyze("SELECT id FROM account WHERE LOWER(name) LIKE ?");

        assertThat(comparisons(profile)).containsExactly("tdb.account.name like");
    }

    @Test
    void aColumnOnThePatternSideIsCriteriaButNotMatched() throws JSQLParserException {
        SqlProfile profile = fixture.analyze("""
            SELECT s.id FROM site s JOIN account a ON a.id = s.account_id WHERE a.name LIKE CONCAT(s.prefix, '%')
            """);

        assertThat(comparisons(profile)).containsExactly("tdb.account.name like");
        assertThat(criteria(profile)).contains("tdb.account.name", "tdb.site.prefix");
    }

    @Test
    void theSameColumnUnderTwoPatternsKeepsBoth() throws JSQLParserException {
        SqlProfile profile = fixture.analyze("""
            SELECT id FROM account WHERE name LIKE 'a%' OR name LIKE '%a' OR name LIKE 'b%'
            """);

        assertThat(comparisons(profile)).containsExactly(
            "tdb.account.name like leading=false", "tdb.account.name like leading=true");
    }

    @Test
    void regexpIsNotLike() throws JSQLParserException {
        SqlProfile profile = fixture.analyze("SELECT id FROM account WHERE name REGEXP ?");

        assertThat(comparisons(profile)).isEmpty();
        assertThat(criteria(profile)).containsExactly("tdb.account.name");
    }

    @Test
    void otherComparisonsAreRecordedByKind() throws JSQLParserException {
        SqlProfile profile = fixture.analyze("""
            SELECT id FROM account
            WHERE status = ? AND id IN (?) AND created_at BETWEEN ? AND ? AND deleted_at IS NULL AND name <> ?
            """);

        assertThat(comparisons(profile)).containsExactlyInAnyOrder(
            "tdb.account.status equality",
            "tdb.account.id in",
            "tdb.account.created_at range",
            "tdb.account.deleted_at null check",
            "tdb.account.name inequality");
    }

    @Test
    void aSubqueryComparesItsOwnColumns() throws JSQLParserException {
        SqlProfile profile = fixture.analyze("""
            SELECT a.id FROM account a
            WHERE a.id NOT IN (SELECT s.account_id FROM site s WHERE s.label LIKE ?)
            """);

        assertThat(comparisons(profile)).containsExactlyInAnyOrder(
            "tdb.account.id not in", "tdb.site.label like");
    }

    @Test
    void likeInAnUpdateIsRecorded() throws JSQLParserException {
        SqlProfile profile = fixture.analyze("UPDATE site SET label = ? WHERE label LIKE '%old'");

        assertThat(comparisons(profile)).containsExactly("tdb.site.label like leading=true");
    }
}
