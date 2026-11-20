package com.github.safetytrick.sqlexplorer.analysis;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two shape facts the workbench filters on (sqlx-6yf), read off real
 * analysis rather than hand-built profiles: how many distinct tables a
 * statement references ({@code distinctTableCount}) and which it joins to
 * themselves ({@code selfJoinedTables}).
 */
public class TableShapeTest {

    private static final SchemaFixture FIXTURE = fixture();

    private static SchemaFixture fixture() {
        try {
            return SchemaFixture.fromSql("""
                create table account (
                    id bigint not null,
                    parent_id bigint,
                    name varchar(255),
                    primary key (id)
                );
                create table site (
                    id bigint not null,
                    name varchar(255),
                    primary key (id)
                );
                create table account_site (
                    account_id bigint not null,
                    site_id bigint not null,
                    primary key (account_id, site_id)
                );
                """);
        } catch (JSQLParserException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void anAliasedSelfJoinIsOneTableJoinedToItself() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze(
            "SELECT c.name FROM account c JOIN account p ON c.parent_id = p.id WHERE p.name = ?");

        assertThat(profile.distinctTableCount()).isEqualTo(1);
        assertThat(profile.selfJoinedTables())
            .extracting(TableReference::getNormalizedTableName).containsExactly("account");
    }

    @Test
    public void aSelfJoinBesideOtherJoinsIsStillFound() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze("""
            SELECT s.name FROM account c
              JOIN account p ON c.parent_id = p.id
              JOIN account_site x ON x.account_id = p.id
              JOIN site s ON s.id = x.site_id""");

        assertThat(profile.distinctTableCount()).isEqualTo(3);
        assertThat(profile.selfJoinedTables())
            .extracting(TableReference::getNormalizedTableName).containsExactly("account");
    }

    @Test
    public void joinsBetweenDifferentTablesAreNotSelfJoins() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze("""
            SELECT s.name FROM account a
              JOIN account_site x ON x.account_id = a.id
              JOIN site s ON s.id = x.site_id""");

        assertThat(profile.distinctTableCount()).isEqualTo(3);
        assertThat(profile.selfJoinedTables()).isEmpty();
    }

    /**
     * The analyzer records {@code x IN (SELECT y ...)} as a join between x and
     * y (a semi-join), so an IN-subquery on the same table is a self-join here,
     * as it is on the graph.
     */
    @Test
    public void anInSubqueryOnTheSameTableIsASemiSelfJoin() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze(
            "SELECT a.name FROM account a WHERE a.id IN (SELECT b.parent_id FROM account b WHERE b.name = ?)");

        assertThat(profile.distinctTableCount()).isEqualTo(1);
        assertThat(profile.selfJoinedTables())
            .extracting(TableReference::getNormalizedTableName).containsExactly("account");
    }

    @Test
    public void aCorrelatedExistsOnTheSameTable() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze(
            "SELECT a.name FROM account a WHERE EXISTS (SELECT 1 FROM account b WHERE b.parent_id = a.id)");

        assertThat(profile.distinctTableCount()).isEqualTo(1);
        assertThat(profile.selfJoinedTables())
            .extracting(TableReference::getNormalizedTableName).containsExactly("account");
    }

    @Test
    public void aTableReadAgainWithoutAJoinConditionIsNoSelfJoin() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze(
            "SELECT a.name FROM account a WHERE a.id = (SELECT max(b.id) FROM account b)");

        assertThat(profile.distinctTableCount()).isEqualTo(1);
        assertThat(profile.selfJoinedTables()).isEmpty();
    }

    @Test
    public void aSingleTableStatementHasOneTable() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze("SELECT name FROM site WHERE id = ?");

        assertThat(profile.distinctTableCount()).isEqualTo(1);
        assertThat(profile.selfJoinedTables()).isEmpty();
    }
}
