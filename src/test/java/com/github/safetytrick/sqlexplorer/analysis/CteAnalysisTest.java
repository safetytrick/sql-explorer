package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CTE (`WITH`) analysis.
 *
 * <p>These statements used to be refused outright: three analyzers threw
 * {@code UnsupportedSqlException("with items list")} on sight of a `WITH`
 * prefix, so a CTE query came back {@code analysisFailed} with no criteria,
 * join or select columns at all — thin everywhere it appeared in the UI.
 */
public class CteAnalysisTest {

    private static final SchemaFixture FIXTURE = fixture();

    private static SchemaFixture fixture() {
        try {
            return SchemaFixture.fromSql("""
                create table node_link (
                    entity_id bigint not null,
                    parent_id bigint,
                    propagate tinyint,
                    object_id bigint,
                    object_type varchar(64),
                    parent_object bigint,
                    parent_type varchar(64),
                    primary key (entity_id)
                );
                create table folders (
                    uuid varchar(36) not null,
                    parent_uuid varchar(36),
                    name varchar(255),
                    primary key (uuid)
                );
                create table users (
                    id bigint not null,
                    category_uuid varchar(36),
                    primary key (id)
                );
                """);
        } catch (JSQLParserException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> tableNames(SqlProfile profile) {
        return profile.getTables().stream().map(t -> t.getNormalizedTableName()).distinct().sorted().toList();
    }

    private static List<String> columnKeys(List<com.github.safetytrick.sqlexplorer.avro.ColumnReference> columns) {
        return columns.stream()
            .map(c -> c.getTable().getNormalizedTableName() + "." + c.getNormalizedColumn())
            .distinct().sorted().toList();
    }

    @Test
    public void analyzesTheTablesAndCriteriaInsideACte() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze("""
            WITH `parent_categories` AS (
                SELECT `uuid`, `parent_uuid`, `name` FROM `folders` WHERE `parent_uuid` = ?
            )
            SELECT `uuid`, `name` FROM `parent_categories`
            """);

        assertThat(profile.getAnalysisFailed()).isFalse();
        assertThat(tableNames(profile)).containsExactly("folders");
        assertThat(columnKeys(profile.getCriteriaColumns())).containsExactly("folders.parent_uuid");
        assertThat(columnKeys(profile.getSelectColumns()))
            .containsExactly("folders.name", "folders.parent_uuid", "folders.uuid");
    }

    @Test
    public void analyzesARecursiveCteWithoutInventingTheCteAsATable() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze("""
            WITH RECURSIVE `tree`(
                `entity_id`, `parent_id`, `propagate`, `object_id`, `object_type`, `parent_object`, `parent_type`
            ) AS (SELECT
                `entity_id`, `entity_id` AS `parent_id`, `propagate`, `object_id`, `object_type`, `parent_object`, `parent_type`
                FROM `node_link`
            WHERE
                `entity_id` = ?
            UNION
            ALL SELECT
                `child`.`entity_id`, `parent`.`entity_id`, `child`.`propagate`, `child`.`object_id`,
                `child`.`object_type`, `child`.`parent_object`, `child`.`parent_type`
                FROM `node_link` `child`
            JOIN
                `tree` `parent`
                    ON `child`.`parent_object` = `parent`.`object_id`
                    AND `child`.`parent_type` = `parent`.`object_type`
            WHERE
                `child`.`object_type` IN (?)) SELECT
                `entity_id`, `parent_id`, `propagate`
            FROM
                `tree`
            """);

        assertThat(profile.getAnalysisFailed()).isFalse();

        // the CTE's source table, not the CTE name
        assertThat(tableNames(profile)).containsExactly("node_link");

        // criteria from inside both arms of the recursion
        assertThat(columnKeys(profile.getCriteriaColumns()))
            .containsExactly("node_link.entity_id", "node_link.object_type");

        // every emitted column belongs to a real table — `tree` appears nowhere
        assertThat(columnKeys(profile.getSelectColumns())).allSatisfy(key ->
            assertThat(key).startsWith("node_link."));
        assertThat(columnKeys(profile.getCriteriaColumns())).noneMatch(key -> key.startsWith("tree."));
        assertThat(profile.getJoinColumns()).allSatisfy(join -> {
            assertThat(join.getLeft().getTable().getNormalizedTableName()).isNotEqualTo("tree");
            assertThat(join.getRight().getTable().getNormalizedTableName()).isNotEqualTo("tree");
        });
    }

    @Test
    public void stillFindsRealJoinsInsideACteBody() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze("""
            WITH `owned` AS (
                SELECT `f`.`uuid`, `u`.`id`
                FROM `folders` `f`
                JOIN `users` `u` ON `u`.`category_uuid` = `f`.`uuid`
                WHERE `f`.`name` = ?
            )
            SELECT `uuid` FROM `owned`
            """);

        assertThat(profile.getAnalysisFailed()).isFalse();
        assertThat(tableNames(profile)).containsExactly("folders", "users");
        assertThat(columnKeys(profile.getCriteriaColumns())).containsExactly("folders.name");

        // the join lives inside the CTE body and is a genuine table-to-table one
        assertThat(profile.getJoinColumns()).hasSize(1);
        assertThat(columnKeys(List.of(profile.getJoinColumns().getFirst().getLeft(),
                                      profile.getJoinColumns().getFirst().getRight())))
            .containsExactly("folders.uuid", "users.category_uuid");
    }

    @Test
    public void aPlainSelectIsUnaffected() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze("SELECT `name` FROM `folders` WHERE `uuid` = ?");

        assertThat(profile.getAnalysisFailed()).isFalse();
        assertThat(tableNames(profile)).containsExactly("folders");
        assertThat(columnKeys(profile.getCriteriaColumns())).containsExactly("folders.uuid");
    }
}
