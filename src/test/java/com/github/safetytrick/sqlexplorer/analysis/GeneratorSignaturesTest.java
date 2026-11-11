package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.GeneratorCandidate;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generator signatures, on made-up statements in each generator's habits
 * (sqlx-n5j.12). A match is a candidate: these tests pin which rules fire, not
 * that a statement "is" anyone's.
 */
class GeneratorSignaturesTest {

    private SchemaFixture fixture;

    @BeforeEach
    void setUp() throws JSQLParserException {
        fixture = SchemaFixture.fromSql("""
            create table account (id bigint not null, name varchar(64), status varchar(16), version int,
                                  primary key (id));
            create table site (id bigint not null, account_id bigint, name varchar(64), primary key (id));
            create table account_site (account_id bigint not null, site_id bigint not null,
                                       primary key (account_id, site_id));
            """);
    }

    private List<String> candidates(String sql) throws JSQLParserException {
        return fixture.analyze(sql).getGeneratorCandidates().stream()
            .map(GeneratorSignaturesTest::describe).toList();
    }

    private static String describe(GeneratorCandidate candidate) {
        return candidate.getGenerator() + ":" + candidate.getRule();
    }

    @Test
    void aHandWrittenSelectMatchesNothing() throws JSQLParserException {
        assertThat(candidates("SELECT a.id, a.name FROM account a WHERE a.status = ?")).isEmpty();
    }

    @Test
    void doctrineDqlWalkerAliases() throws JSQLParserException {
        assertThat(candidates("""
            SELECT `c0_`.`id` AS `id_0`, `c0_`.`name` AS `name_1` FROM `account` `c0_`
            INNER JOIN `site` `s1_` ON `s1_`.`account_id` = `c0_`.`id` WHERE `c0_`.`status` = ?
            """)).containsExactly("doctrine-orm:dql-walker-alias");
    }

    @Test
    void doctrineEntityPersister() throws JSQLParserException {
        assertThat(candidates("SELECT t0.id AS id_1, t0.name AS name_2 FROM account t0 WHERE t0.id = ?"))
            .containsExactly("doctrine-orm:persister-alias");
    }

    /** t0 alone is not enough: plenty of hand-written SQL calls a table t0. */
    @Test
    void aTableAliasedT0WithoutPersisterColumnsIsNotThePersister() throws JSQLParserException {
        assertThat(candidates("SELECT t0.id, t0.name FROM account t0 WHERE t0.id = ?")).isEmpty();
    }

    @Test
    void doctrineManyToManyLoader() throws JSQLParserException {
        assertThat(candidates("""
            SELECT c.id, c.name FROM site c JOIN account_site j ON (j.site_id = c.id) WHERE j.account_id = ?
            """)).containsExactly("doctrine-orm:many-to-many-loader");
    }

    @Test
    void hibernateCriteriaRoot() throws JSQLParserException {
        assertThat(candidates("SELECT `this_`.`id` FROM `account` `this_` WHERE `this_`.`status` = ?"))
            .containsExactly("hibernate:criteria-root-alias");
    }

    @Test
    void hibernateLegacyAliases() throws JSQLParserException {
        assertThat(candidates("""
            SELECT account0_.id AS id92_0_, account0_.name AS name92_0_ FROM account account0_
            WHERE account0_.id = ?
            """)).containsExactlyInAnyOrder("hibernate:select-alias", "hibernate:hql-table-alias");
    }

    @Test
    void hibernateSixAliases() throws JSQLParserException {
        assertThat(candidates("SELECT a1_0.id, a1_0.name FROM account a1_0 WHERE a1_0.id = ?"))
            .containsExactly("hibernate:h6-table-alias");
    }

    /**
     * THE REASON A MATCH IS ONLY A CANDIDATE. Both ORMs write a single-row
     * insert of parameters, and so does any code once Performance Schema has
     * replaced the literals — so both generators claim it.
     */
    @Test
    void aSimpleInsertIsEveryOrmsCandidate() throws JSQLParserException {
        assertThat(candidates("INSERT INTO account (id, name, status) VALUES (?, ?, ?)"))
            .containsExactly("doctrine-orm:uow-insert", "hibernate:uow-insert");
    }

    @Test
    void aSimpleUpdateIsEveryOrmsCandidate() throws JSQLParserException {
        assertThat(candidates("UPDATE account SET name = ?, status = ? WHERE id = ?"))
            .containsExactly("doctrine-orm:uow-update", "hibernate:uow-update");
        assertThat(candidates("UPDATE account SET name = ? WHERE id = ? AND version = ?"))
            .containsExactly("doctrine-orm:uow-update", "hibernate:uow-update");
    }

    @Test
    void writesThatAreNotUnitOfWorkShapedMatchNothing() throws JSQLParserException {
        assertThat(candidates("INSERT INTO account (id, name) VALUES (?, ?), (?, ?)")).isEmpty();
        assertThat(candidates("INSERT INTO account (id, name) VALUES (?, 'fixed')")).isEmpty();
        assertThat(candidates("INSERT INTO account (id, name) VALUES (?, ?) ON DUPLICATE KEY UPDATE name = ?"))
            .isEmpty();
        assertThat(candidates("UPDATE account SET version = version + 1 WHERE id = ?")).isEmpty();
        assertThat(candidates("UPDATE account SET name = ? WHERE status IN (?, ?)")).isEmpty();
        assertThat(candidates("UPDATE account a SET a.name = ? WHERE a.id = ?")).isEmpty();
    }

    @Test
    void everyGeneratorIsKnownByName() {
        assertThat(GeneratorSignatures.known("doctrine-orm")).isTrue();
        assertThat(GeneratorSignatures.known("hibernate")).isTrue();
        assertThat(GeneratorSignatures.known("nope")).isFalse();
    }
}
