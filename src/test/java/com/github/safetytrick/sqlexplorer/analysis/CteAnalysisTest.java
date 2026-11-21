package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
                    owner_group bigint,
                    primary key (entity_id)
                );
                create table folders (
                    id bigint,
                    uuid varchar(36) not null,
                    parent_uuid varchar(36),
                    parent_id bigint,
                    name varchar(255),
                    level int,
                    status varchar(32),
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

    private static final String RECURSIVE_TREE = """
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
            `child`.`object_type`, `child`.`parent_type`, `child`.`parent_type`
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
        """;

    private static List<String> joinKeys(SqlProfile profile) {
        return profile.getJoinColumns().stream()
            .map(join -> key(join.getLeft()) + "=" + key(join.getRight()))
            .sorted().toList();
    }

    private static String key(com.github.safetytrick.sqlexplorer.avro.ColumnReference reference) {
        return reference.getTable().getNormalizedTableName() + "." + reference.getNormalizedColumn();
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

        assertThat(columnKeys(profile.getCriteriaColumns()))
            .containsExactly("node_link.entity_id", "node_link.object_type");

        assertThat(columnKeys(profile.getSelectColumns())).allSatisfy(key ->
            assertThat(key).startsWith("node_link."));
        assertThat(columnKeys(profile.getCriteriaColumns())).noneMatch(key -> key.startsWith("tree."));
        assertThat(profile.getJoinColumns()).allSatisfy(join -> {
            assertThat(join.getLeft().getTable().getNormalizedTableName()).isNotEqualTo("tree");
            assertThat(join.getRight().getTable().getNormalizedTableName()).isNotEqualTo("tree");
        });
    }

    @Test
    public void resolvesAJoinThroughACteToItsSourceColumns() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze(RECURSIVE_TREE);

        assertThat(profile.getAnalysisFailed()).isFalse();
        assertThat(joinKeys(profile))
            .contains("node_link.parent_object=node_link.object_id")
            .contains("node_link.parent_type=node_link.object_type");
    }

    @Test
    public void mapsThroughWithoutEmittingTheCteAsATable() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze(RECURSIVE_TREE);

        assertThat(tableNames(profile)).containsExactly("node_link");
        assertThat(joinKeys(profile)).noneMatch(key -> key.contains("tree."));
    }

    @Test
    public void leavesAJoinThroughAProjectedExpressionAlone() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze("""
            WITH `counts`(`category_uuid`, `n`) AS (
                SELECT `category_uuid`, count(*) FROM `users` GROUP BY `category_uuid`
            )
            SELECT `f`.`name`
            FROM `folders` `f`
            JOIN `counts` `c` ON `f`.`uuid` = `c`.`n`
            """);

        assertThat(profile.getAnalysisFailed()).isFalse();
        assertThat(joinKeys(profile)).noneMatch(key -> key.contains("counts."));
        assertThat(joinKeys(profile)).noneMatch(key -> key.endsWith(".n"));
    }

    @Test
    public void mapsThroughACteThatInheritsItsColumnNames() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze("""
            WITH `recent` AS (
                SELECT `uuid`, `parent_uuid` FROM `folders` WHERE `status` = ?
            )
            SELECT `u`.`id`
            FROM `users` `u`
            JOIN `recent` `r` ON `u`.`category_uuid` = `r`.`uuid`
            """);

        assertThat(profile.getAnalysisFailed()).isFalse();
        assertThat(joinKeys(profile)).contains("users.category_uuid=folders.uuid");
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

    private static boolean everyColumnBelongsToARealTable(SqlProfile profile) {
        java.util.Set<String> real = profile.getTables().stream()
            .map(t -> t.getNormalizedTableName()).collect(java.util.stream.Collectors.toSet());
        java.util.List<com.github.safetytrick.sqlexplorer.avro.ColumnReference> all =
            new java.util.ArrayList<>();
        all.addAll(profile.getCriteriaColumns());
        all.addAll(profile.getSelectColumns());
        profile.getJoinColumns().forEach(j -> { all.add(j.getLeft()); all.add(j.getRight()); });
        return all.stream()
            .filter(c -> c != null && c.getTable() != null)
            .allMatch(c -> real.contains(c.getTable().getNormalizedTableName()));
    }

    @Test
    public void corpusCte1AnalyzesWithoutFailing() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze("""
            WITH RECURSIVE `tree`(
                    `entity_id`, `parent_id`, `propagate`, `object_id`, `object_type`, `parent_object`, `parent_type`
                ) AS (SELECT
                    `entity_id`, `entity_id` AS `parent_id`, `propagate`, `object_id`, `object_type`, `parent_object`, `parent_type` FROM `node_link` 
                WHERE
                    `entity_id` = ? 
                UNION
                ALL SELECT
                    `child`.`entity_id`, `parent`.`entity_id`, `child`.`propagate`, `child`.`object_id`, `child`.`object_type`, `child`.`parent_object`, `child`.`parent_type` FROM `node_link` `child` 
                JOIN
                    `tree` `parent` 
                        ON `child`.`parent_object` = `parent`.`object_id` 
                        AND `child`.`parent_type` = `parent`.`object_type` 
                WHERE
                    `child`.`object_type` IN (?)) SELECT
                    `entity_id`,
                    `parent_id`,
                    `propagate` 
                FROM
                    `tree`
            """);

        assertThat(profile.getAnalysisFailureReason()).isNull();
        assertThat(profile.getAnalysisFailed()).isFalse();
        assertThat(profile.getTables()).isNotEmpty();
        assertThat(everyColumnBelongsToARealTable(profile)).isTrue();
    }

    @Test
    public void corpusCte2AnalyzesWithoutFailing() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze("""
            WITH RECURSIVE `parent_categories` AS (SELECT
                    `uuid`, `parent_id`, NAME, LEVEL FROM `folders` 
                WHERE
                    `uuid` = ? 
                UNION
                ALL SELECT
                    `f`.`uuid`, `f`.`parent_id`, `f`.`name`, `f`.`level` FROM `folders` `f` 
                INNER JOIN
                    `parent_categories` `pf` 
                        ON `f`.`id` = `pf`.`parent_id` 
                WHERE
                    STATUS = ?) SELECT
                    * 
                FROM
                    `parent_categories` 
                ORDER BY
                    `level`
            """);

        assertThat(profile.getAnalysisFailureReason()).isNull();
        assertThat(profile.getAnalysisFailed()).isFalse();
        assertThat(profile.getTables()).isNotEmpty();
        assertThat(everyColumnBelongsToARealTable(profile)).isTrue();
    }

    @Test
    public void corpusCte3AnalyzesWithoutFailing() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze("""
            WITH RECURSIVE `tree`(
                    `entity_id`, `parent_id`, `propagate`, `object_id`, `object_type`, `parent_object`, `parent_type`, `owner_group`
                ) AS (SELECT
                    `entity_id`, `entity_id` AS `parent_id`, `propagate`, `object_id`, `object_type`, `parent_object`, `parent_type`, `owner_group` FROM `node_link` 
                WHERE
                    `entity_id` IN (?) 
                UNION
                ALL SELECT
                    `child`.`entity_id`, `parent`.`entity_id`, `child`.`propagate`, `child`.`object_id`, `child`.`object_type`, `child`.`parent_object`, `child`.`parent_type`, `child`.`owner_group` FROM `node_link` `child` 
                JOIN
                    `tree` `parent` 
                        ON `child`.`parent_object` = `parent`.`object_id` 
                        AND `child`.`parent_type` = `parent`.`object_type`) SELECT
                        `entity_id`,
                        `object_id`,
                        `owner_group`,
                        `parent_id`,
                        `parent_object`,
                        `parent_type`,
                        `propagate` 
                FROM
                    `tree`
            """);

        assertThat(profile.getAnalysisFailureReason()).isNull();
        assertThat(profile.getAnalysisFailed()).isFalse();
        assertThat(profile.getTables()).isNotEmpty();
        assertThat(everyColumnBelongsToARealTable(profile)).isTrue();
    }
}
