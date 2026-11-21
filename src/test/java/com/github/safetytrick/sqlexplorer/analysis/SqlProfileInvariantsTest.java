package com.github.safetytrick.sqlexplorer.analysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.model.MergedSqlProfile;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

public class SqlProfileInvariantsTest {

    private static final SchemaFixture FIXTURE = fixture();

    private static SchemaFixture fixture() {
        try {
            return SchemaFixture.fromSql("""
                create table category (
                    id bigint,
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
                create table other_db.audit_log (
                    id bigint not null,
                    category_uuid varchar(36),
                    primary key (id)
                );
                """);
        } catch (JSQLParserException e) {
            throw new IllegalStateException(e);
        }
    }

    static Stream<Arguments> everyProfileShape() throws JSQLParserException {
        Map<String, SqlProfile> shapes = new LinkedHashMap<>();

        shapes.put("analyzed", FIXTURE.analyze(
            "SELECT `name` FROM `category` WHERE `uuid` = ? GROUP BY `name`"));

        shapes.put("analyzed, joined", FIXTURE.analyze(
            "SELECT `f`.`name` FROM `category` `f` JOIN `users` `u` ON `u`.`category_uuid` = `f`.`uuid`"));

        shapes.put("analyzed, cross-schema", FIXTURE.analyze(
            "SELECT `f`.`name` FROM `category` `f` "
                + "JOIN `other_db`.`audit_log` `a` ON `a`.`category_uuid` = `f`.`uuid`"));

        shapes.put("analysis failed", FIXTURE.analyze("SELECT\n        NOW"));

        shapes.put("cte", FIXTURE.analyze("""
            WITH `owned` AS (
                SELECT `f`.`uuid`, `u`.`id`
                FROM `category` `f`
                JOIN `users` `u` ON `u`.`category_uuid` = `f`.`uuid`
                WHERE `f`.`name` = ?
            )
            SELECT `uuid` FROM `owned`
            """));

        shapes.put("analysis skipped", skipped(List.of(
            TableReference.fromString("mysql.triggers"),
            TableReference.fromString("information_schema.triggers"))));

        shapes.put("analysis skipped, one schema", skipped(List.of(
            TableReference.fromString("mysql.triggers"))));

        shapes.put("analysis skipped, no tables", skipped(List.of()));

        shapes.put("analysis skipped, another schema", skipped("mysql", List.of(
            TableReference.fromString("information_schema.tables"))));

        SqlProfile a = FIXTURE.analyze("SELECT `name` FROM `category` WHERE `uuid` = ?");
        SqlProfile b = FIXTURE.analyze("SELECT `name` FROM `category` WHERE `uuid` = ?");
        shapes.put("merged", new MergedSqlProfile(List.of(a, b)));

        shapes.put("merged, skipped", new MergedSqlProfile(List.of(
            skipped(List.of(TableReference.fromString("mysql.triggers"))),
            skipped(List.of(TableReference.fromString("mysql.triggers"))))));

        SqlProfile x = FIXTURE.analyze(
            "SELECT `f`.`name` FROM `category` `f` "
                + "JOIN `other_db`.`audit_log` `a` ON `a`.`category_uuid` = `f`.`uuid`");
        shapes.put("merged, cross-schema", new MergedSqlProfile(List.of(x, x)));

        return shapes.entrySet().stream()
            .map(e -> Arguments.of(Named.of(e.getKey(), e.getValue())));
    }

    private static SqlProfile skipped(List<TableReference> tables) {
        return skipped("mysql", tables);
    }

    private static SqlProfile skipped(String sessionSchema, List<TableReference> tables) {
        return SqlProfile.newBuilder()
            .setId("skipped")
            .setDigest("skipped")
            .setNormalizedDigest("skipped")
            .setQuery("SELECT 1 FROM somewhere")
            .setType("SELECT")
            .setSchemaName(sessionSchema)
            .setQueryActivityStatus(QueryActivityStatus.ACTIVE)
            .setRuntimeMetrics(null)
            .setTables(tables)
            .setAnalysisSkipped(true)
            .completeBuild(sessionSchema);
    }

    @ParameterizedTest(name = "no null lists [{0}]")
    @MethodSource("everyProfileShape")
    public void listFieldsAreNeverNull(SqlProfile profile) {
        assertThat(profile.getTables()).as("tables").isNotNull();
        assertThat(profile.getJoinColumns()).as("joinColumns").isNotNull();
        assertThat(profile.getCriteriaColumns()).as("criteriaColumns").isNotNull();
        assertThat(profile.getSelectColumns()).as("selectColumns").isNotNull();
        assertThat(profile.getGroupByColumns()).as("groupByColumns").isNotNull();
        assertThat(profile.getIndexHints()).as("indexHints").isNotNull();
        assertThat(profile.getSchemas()).as("schemas").isNotNull();
        assertThat(profile.getMismatchedJoins()).as("mismatchedJoins").isNotNull();
        assertThat(profile.getSourcedFiles()).as("sourcedFiles").isNotNull();
    }

    @ParameterizedTest(name = "schemas>1 implies isMultiSchema [{0}]")
    @MethodSource("everyProfileShape")
    public void moreThanOneSchemaMeansMultiSchema(SqlProfile profile) {
        if (profile.getSchemas().size() > 1) {
            assertThat(profile.getIsMultiSchema())
                .as("lists %s but does not report multi-schema", profile.getSchemas())
                .isTrue();
        }
    }

    @ParameterizedTest(name = "isMultiSchema implies schemas>1 [{0}]")
    @MethodSource("everyProfileShape")
    public void multiSchemaMeansMoreThanOneSchema(SqlProfile profile) {
        if (profile.getIsMultiSchema()) {
            assertThat(profile.getSchemas())
                .as("reports multi-schema but lists %s", profile.getSchemas())
                .hasSizeGreaterThan(1);
        }
    }

    @Test
    public void aSkippedStatementDigestsPerSchema() {
        SqlProfile inMsg = skippedWithMysqlDigest("msg");
        SqlProfile inOther = skippedWithMysqlDigest("app_other");

        assertThat(inMsg.getNormalizedDigest())
            .as("same text, different schema, must not be one profile")
            .isNotEqualTo(inOther.getNormalizedDigest());
        assertThat(inMsg.getDigest()).isEqualTo(inMsg.getNormalizedDigest());

        assertThat(skippedWithMysqlDigest("msg").getNormalizedDigest())
            .as("same text, same schema, still one profile")
            .isEqualTo(inMsg.getNormalizedDigest());
    }

    private static SqlProfile skippedWithMysqlDigest(String sessionSchema) {
        return SqlProfile.newBuilder()
            .setQuery("SHOW INDEXES FROM `tokens`")
            .setType("SHOW")
            .setDigest("a-mysql-digest-shared-across-schemas")
            .setSchemaName(sessionSchema)
            .setQueryActivityStatus(QueryActivityStatus.ACTIVE)
            .setRuntimeMetrics(null)
            .setTables(List.of(TableReference.fromString(sessionSchema + ".tokens")))
            .setAnalysisSkipped(true)
            .completeBuild(sessionSchema);
    }

    @ParameterizedTest(name = "one schema names itself [{0}]")
    @MethodSource("everyProfileShape")
    public void oneSchemaMeansSchemaNameIsThatSchema(SqlProfile profile) {
        if (profile.getSchemas().size() == 1) {
            assertThat(profile.getSchemaName())
                .as("lists only %s but calls itself %s", profile.getSchemas(), profile.getSchemaName())
                .isEqualTo(profile.getSchemas().getFirst());
        }
    }

    @ParameterizedTest(name = "schemas covers tables [{0}]")
    @MethodSource("everyProfileShape")
    public void schemasCoversEveryTablesSchema(SqlProfile profile) {
        List<String> tableSchemas = profile.getTables().stream()
            .map(TableReference::getNormalizedSchemaName)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();

        assertThat(profile.getSchemas())
            .as("tables live in %s; schemas lists %s", tableSchemas, profile.getSchemas())
            .containsAll(tableSchemas);
    }

    @ParameterizedTest(name = "identity present [{0}]")
    @MethodSource("everyProfileShape")
    public void identityFieldsArePresent(SqlProfile profile) {
        assertThat(profile.getId()).as("id").isNotNull();
        assertThat(profile.getDigest()).as("digest").isNotNull();
        assertThat(profile.getNormalizedDigest()).as("normalizedDigest").isNotNull();
        assertThat(profile.getSchemaName()).as("schemaName").isNotNull();
    }

    @ParameterizedTest(name = "every getter answers [{0}]")
    @MethodSource("everyProfileShape")
    public void everyGetterIsCallable(SqlProfile profile) {
        for (java.lang.reflect.Method getter : SqlProfile.class.getDeclaredMethods()) {
            if (!getter.getName().startsWith("get")
                || getter.getParameterCount() != 0
                || java.lang.reflect.Modifier.isStatic(getter.getModifiers())) {
                continue;
            }
            assertThatCode(() -> getter.invoke(profile))
                .as("%s", getter.getName())
                .doesNotThrowAnyException();
        }
    }

    @ParameterizedTest(name = "failure flag and reason agree [{0}]")
    @MethodSource("everyProfileShape")
    public void aFailureReasonImpliesTheFailureFlag(SqlProfile profile) {
        if (profile.getAnalysisFailureReason() != null) {
            assertThat(profile.getAnalysisFailed())
                .as("has reason %s but is not marked failed", profile.getAnalysisFailureReason())
                .isTrue();
        }
    }
}
