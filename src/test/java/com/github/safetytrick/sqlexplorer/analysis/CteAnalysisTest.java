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
                    link_id bigint not null,
                    parent_id bigint,
                    inherits tinyint,
                    item_id bigint,
                    item_type varchar(64),
                    parent_item bigint,
                    parent_type varchar(64),
                    owner_team bigint,
                    primary key (link_id)
                );
                create table category (
                    id bigint,
                    uuid varchar(36) not null,
                    parent_uuid varchar(36),
                    parent_id bigint,
                    name varchar(255),
                    level int,
                    status varchar(32),
                    primary key (uuid)
                );
                create table account (
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
        WITH RECURSIVE `lineage`(
            `link_id`, `parent_id`, `inherits`, `item_id`, `item_type`, `parent_item`, `parent_type`
        ) AS (SELECT
            `link_id`, `link_id` AS `parent_id`, `inherits`, `item_id`, `item_type`, `parent_item`, `parent_type`
            FROM `node_link`
        WHERE
            `link_id` = ?
        UNION
        ALL SELECT
            `child`.`link_id`, `parent`.`link_id`, `child`.`inherits`, `child`.`item_id`,
            `child`.`item_type`, `child`.`parent_type`, `child`.`parent_type`
            FROM `node_link` `child`
        JOIN
            `lineage` `parent`
                ON `child`.`parent_item` = `parent`.`item_id`
                AND `child`.`parent_type` = `parent`.`item_type`
        WHERE
            `child`.`item_type` IN (?)) SELECT
            `link_id`, `parent_id`, `inherits`
        FROM
            `lineage`
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
                SELECT `uuid`, `parent_uuid`, `name` FROM `category` WHERE `parent_uuid` = ?
            )
            SELECT `uuid`, `name` FROM `parent_categories`
            """);

        assertThat(profile.getAnalysisFailed()).isFalse();
        assertThat(tableNames(profile)).containsExactly("category");
        assertThat(columnKeys(profile.getCriteriaColumns())).containsExactly("category.parent_uuid");
        assertThat(columnKeys(profile.getSelectColumns()))
            .containsExactly("category.name", "category.parent_uuid", "category.uuid");
    }

    @Test
    public void analyzesARecursiveCteWithoutInventingTheCteAsATable() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze("""
            WITH RECURSIVE `lineage`(
                `link_id`, `parent_id`, `inherits`, `item_id`, `item_type`, `parent_item`, `parent_type`
            ) AS (SELECT
                `link_id`, `link_id` AS `parent_id`, `inherits`, `item_id`, `item_type`, `parent_item`, `parent_type`
                FROM `node_link`
            WHERE
                `link_id` = ?
            UNION
            ALL SELECT
                `child`.`link_id`, `parent`.`link_id`, `child`.`inherits`, `child`.`item_id`,
                `child`.`item_type`, `child`.`parent_item`, `child`.`parent_type`
                FROM `node_link` `child`
            JOIN
                `lineage` `parent`
                    ON `child`.`parent_item` = `parent`.`item_id`
                    AND `child`.`parent_type` = `parent`.`item_type`
            WHERE
                `child`.`item_type` IN (?)) SELECT
                `link_id`, `parent_id`, `inherits`
            FROM
                `lineage`
            """);

        assertThat(profile.getAnalysisFailed()).isFalse();

        assertThat(tableNames(profile)).containsExactly("node_link");

        assertThat(columnKeys(profile.getCriteriaColumns()))
            .containsExactly("node_link.item_type", "node_link.link_id");

        assertThat(columnKeys(profile.getSelectColumns())).allSatisfy(key ->
            assertThat(key).startsWith("node_link."));
        assertThat(columnKeys(profile.getCriteriaColumns())).noneMatch(key -> key.startsWith("lineage."));
        assertThat(profile.getJoinColumns()).allSatisfy(join -> {
            assertThat(join.getLeft().getTable().getNormalizedTableName()).isNotEqualTo("lineage");
            assertThat(join.getRight().getTable().getNormalizedTableName()).isNotEqualTo("lineage");
        });
    }

    @Test
    public void resolvesAJoinThroughACteToItsSourceColumns() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze(RECURSIVE_TREE);

        assertThat(profile.getAnalysisFailed()).isFalse();
        assertThat(joinKeys(profile))
            .contains("node_link.parent_item=node_link.item_id")
            .contains("node_link.parent_type=node_link.item_type");
    }

    @Test
    public void mapsThroughWithoutEmittingTheCteAsATable() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze(RECURSIVE_TREE);

        assertThat(tableNames(profile)).containsExactly("node_link");
        assertThat(joinKeys(profile)).noneMatch(key -> key.contains("lineage."));
    }

    @Test
    public void leavesAJoinThroughAProjectedExpressionAlone() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze("""
            WITH `counts`(`category_uuid`, `n`) AS (
                SELECT `category_uuid`, count(*) FROM `account` GROUP BY `category_uuid`
            )
            SELECT `f`.`name`
            FROM `category` `f`
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
                SELECT `uuid`, `parent_uuid` FROM `category` WHERE `status` = ?
            )
            SELECT `u`.`id`
            FROM `account` `u`
            JOIN `recent` `r` ON `u`.`category_uuid` = `r`.`uuid`
            """);

        assertThat(profile.getAnalysisFailed()).isFalse();
        assertThat(joinKeys(profile)).contains("account.category_uuid=category.uuid");
    }

    @Test
    public void stillFindsRealJoinsInsideACteBody() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze("""
            WITH `owned` AS (
                SELECT `f`.`uuid`, `u`.`id`
                FROM `category` `f`
                JOIN `account` `u` ON `u`.`category_uuid` = `f`.`uuid`
                WHERE `f`.`name` = ?
            )
            SELECT `uuid` FROM `owned`
            """);

        assertThat(profile.getAnalysisFailed()).isFalse();
        assertThat(tableNames(profile)).containsExactly("account", "category");
        assertThat(columnKeys(profile.getCriteriaColumns())).containsExactly("category.name");

        assertThat(profile.getJoinColumns()).hasSize(1);
        assertThat(columnKeys(List.of(profile.getJoinColumns().getFirst().getLeft(),
                                      profile.getJoinColumns().getFirst().getRight())))
            .containsExactly("account.category_uuid", "category.uuid");
    }

    @Test
    public void aPlainSelectIsUnaffected() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze("SELECT `name` FROM `category` WHERE `uuid` = ?");

        assertThat(profile.getAnalysisFailed()).isFalse();
        assertThat(tableNames(profile)).containsExactly("category");
        assertThat(columnKeys(profile.getCriteriaColumns())).containsExactly("category.uuid");
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
    public void recursiveCte1AnalyzesWithoutFailing() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze("""
            WITH RECURSIVE `lineage`(
                    `link_id`, `parent_id`, `inherits`, `item_id`, `item_type`, `parent_item`, `parent_type`
                ) AS (SELECT
                    `link_id`, `link_id` AS `parent_id`, `inherits`, `item_id`, `item_type`, `parent_item`, `parent_type` FROM `node_link` 
                WHERE
                    `link_id` = ? 
                UNION
                ALL SELECT
                    `child`.`link_id`, `parent`.`link_id`, `child`.`inherits`, `child`.`item_id`, `child`.`item_type`, `child`.`parent_item`, `child`.`parent_type` FROM `node_link` `child` 
                JOIN
                    `lineage` `parent` 
                        ON `child`.`parent_item` = `parent`.`item_id` 
                        AND `child`.`parent_type` = `parent`.`item_type` 
                WHERE
                    `child`.`item_type` IN (?)) SELECT
                    `link_id`,
                    `parent_id`,
                    `inherits` 
                FROM
                    `lineage`
            """);

        assertThat(profile.getAnalysisFailureReason()).isNull();
        assertThat(profile.getAnalysisFailed()).isFalse();
        assertThat(profile.getTables()).isNotEmpty();
        assertThat(everyColumnBelongsToARealTable(profile)).isTrue();
    }

    @Test
    public void recursiveCte2AnalyzesWithoutFailing() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze("""
            WITH RECURSIVE `parent_categories` AS (SELECT
                    `uuid`, `parent_id`, NAME, LEVEL FROM `category` 
                WHERE
                    `uuid` = ? 
                UNION
                ALL SELECT
                    `f`.`uuid`, `f`.`parent_id`, `f`.`name`, `f`.`level` FROM `category` `f` 
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
    public void recursiveCte3AnalyzesWithoutFailing() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze("""
            WITH RECURSIVE `lineage`(
                    `link_id`, `parent_id`, `inherits`, `item_id`, `item_type`, `parent_item`, `parent_type`, `owner_team`
                ) AS (SELECT
                    `link_id`, `link_id` AS `parent_id`, `inherits`, `item_id`, `item_type`, `parent_item`, `parent_type`, `owner_team` FROM `node_link` 
                WHERE
                    `link_id` IN (?) 
                UNION
                ALL SELECT
                    `child`.`link_id`, `parent`.`link_id`, `child`.`inherits`, `child`.`item_id`, `child`.`item_type`, `child`.`parent_item`, `child`.`parent_type`, `child`.`owner_team` FROM `node_link` `child` 
                JOIN
                    `lineage` `parent` 
                        ON `child`.`parent_item` = `parent`.`item_id` 
                        AND `child`.`parent_type` = `parent`.`item_type`) SELECT
                        `link_id`,
                        `item_id`,
                        `owner_team`,
                        `parent_id`,
                        `parent_item`,
                        `parent_type`,
                        `inherits` 
                FROM
                    `lineage`
            """);

        assertThat(profile.getAnalysisFailureReason()).isNull();
        assertThat(profile.getAnalysisFailed()).isFalse();
        assertThat(profile.getTables()).isNotEmpty();
        assertThat(everyColumnBelongsToARealTable(profile)).isTrue();
    }
}
