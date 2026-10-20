package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.IndexHint;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Index hints are recorded one entry per named index, against the hinted table (sqlx-yj0). */
class IndexHintAnalyzerTest {

    private SchemaFixture fixture;

    @BeforeEach
    void setUp() throws JSQLParserException {
        fixture = SchemaFixture.fromSql("""
            create table account (id bigint not null, status varchar(16), owner_id bigint, primary key (id),
                                  key account_status_idx (status), key account_owner_idx (owner_id));
            create table site (id bigint not null, account_id bigint, primary key (id),
                               key site_account_idx (account_id));
            """);
    }

    private static List<String> hints(SqlProfile profile) {
        return profile.getIndexHints().stream().map(IndexHintAnalyzerTest::describe).toList();
    }

    private static String describe(IndexHint hint) {
        return hint.getAction() + " " + hint.getTable().toStringNormalized() + "." + hint.getNormalizedIndex();
    }

    @Test
    void aStatementWithoutHintsHasNone() throws JSQLParserException {
        assertThat(fixture.analyze("SELECT id FROM account WHERE status = ?").getIndexHints()).isEmpty();
    }

    @Test
    void eachNamedIndexIsOneHint() throws JSQLParserException {
        SqlProfile profile = fixture.analyze(
            "SELECT id FROM account FORCE INDEX (account_status_idx, account_owner_idx) WHERE status = ?");

        assertThat(hints(profile)).containsExactly(
            "FORCE tdb.account.account_status_idx", "FORCE tdb.account.account_owner_idx");
    }

    @Test
    void anAliasedTablesHintIsTheTablesNotTheAlias() throws JSQLParserException {
        SqlProfile profile = fixture.analyze("""
            SELECT `a`.`id` FROM `account` `a` USE INDEX (`account_status_idx`)
            JOIN `site` `s` IGNORE KEY (PRIMARY) ON `s`.`account_id` = `a`.`id`
            """);

        assertThat(hints(profile)).containsExactlyInAnyOrder(
            "USE tdb.account.account_status_idx", "IGNORE tdb.site.primary");
        assertThat(profile.getIndexHints()).extracting(IndexHint::getIndex)
            .contains("`account_status_idx`");
    }

    @Test
    void aSchemaQualifiedTableKeepsItsSchema() throws JSQLParserException {
        SqlProfile profile = fixture.analyze("SELECT id FROM other.account FORCE INDEX (account_status_idx)");

        assertThat(hints(profile)).containsExactly("FORCE other.account.account_status_idx");
    }

    @Test
    void hintsInsideASubqueryAndOnAnUpdateAreRecorded() throws JSQLParserException {
        assertThat(hints(fixture.analyze("""
            SELECT id FROM account
            WHERE id IN (SELECT account_id FROM site FORCE INDEX (site_account_idx))
            """))).containsExactly("FORCE tdb.site.site_account_idx");

        assertThat(hints(fixture.analyze(
            "UPDATE account FORCE INDEX (account_owner_idx) SET status = ? WHERE owner_id = ?")))
            .containsExactly("FORCE tdb.account.account_owner_idx");
    }
}
