package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WriteTablesAnalyzerTest {

    private SchemaFixture fixture;

    @BeforeEach
    void setUp() throws JSQLParserException {
        fixture = SchemaFixture.fromSql("""
            create table account (id bigint not null, status varchar(16), primary key (id));
            create table site (id bigint not null, account_id bigint, name varchar(64), primary key (id));
            create table account_site (account_id bigint, site_id bigint, key account_site_idx (account_id));
            """);
    }

    private List<String> written(String sql) throws JSQLParserException {
        SqlProfile profile = fixture.analyze(sql);
        return profile.getWriteTables().stream().map(TableReference::getNormalizedTableName).toList();
    }

    @Test
    void anInsertSelectWritesItsTargetNotWhatItReads() throws JSQLParserException {
        assertThat(written("INSERT INTO account_site (account_id, site_id) SELECT account_id, id FROM site"))
            .containsExactly("account_site");
        assertThat(written("REPLACE INTO account (id, status) VALUES (?, ?)")).containsExactly("account");
    }

    @Test
    void anUpdateWritesTheTablesItSets() throws JSQLParserException {
        assertThat(written("UPDATE account SET status = ? WHERE id = ?")).containsExactly("account");
        assertThat(written("UPDATE site s JOIN account a ON a.id = s.account_id SET s.name = ? WHERE a.status = ?"))
            .containsExactly("site");
        assertThat(written("UPDATE site s JOIN account a ON a.id = s.account_id SET s.name = ?, a.status = ?"))
            .containsExactly("site", "account");
        assertThat(written("UPDATE account, site SET site.name = ? WHERE site.account_id = account.id"))
            .containsExactly("site");
    }

    @Test
    void anUnqualifiedSetColumnBelongsToTheFirstTable() throws JSQLParserException {
        assertThat(written("UPDATE account a JOIN site s ON s.account_id = a.id SET status = ?"))
            .containsExactly("account");
    }

    @Test
    void aDeleteWritesItsTargetsNotItsSubqueryOrJoins() throws JSQLParserException {
        assertThat(written("DELETE FROM site WHERE account_id IN (SELECT id FROM account WHERE status = ?)"))
            .containsExactly("site");
        assertThat(written("DELETE s FROM site s JOIN account a ON a.id = s.account_id WHERE a.status = ?"))
            .containsExactly("site");
        assertThat(written("DELETE s, x FROM site s JOIN account_site x ON x.site_id = s.id"))
            .containsExactly("site", "account_site");
    }

    @Test
    void aCteFeedingAWriteWritesOnlyTheTarget() throws JSQLParserException {
        assertThat(written("WITH stale AS (SELECT id FROM account WHERE status = ?) "
            + "DELETE FROM site WHERE account_id IN (SELECT id FROM stale)")).containsExactly("site");
    }

    @Test
    void aReadWritesNothing() throws JSQLParserException {
        assertThat(written("SELECT s.id FROM site s JOIN account a ON a.id = s.account_id")).isEmpty();
    }

    @Test
    void writtenTablesTakeTheDefaultSchemaLikeTheRest() throws JSQLParserException {
        SqlProfile profile = fixture.analyze("UPDATE account SET status = ?");

        assertThat(profile.getWriteTables()).singleElement()
            .satisfies(table -> assertThat(profile.getTables()).anyMatch(table::equalsNormalized));
    }
}
