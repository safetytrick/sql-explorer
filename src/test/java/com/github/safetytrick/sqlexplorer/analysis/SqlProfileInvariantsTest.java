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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-field invariants on a built {@link SqlProfile}.
 *
 * <p>Three bugs in quick succession shared one shape: a field derived on one
 * code path and consumed alongside a field derived on another, with nothing
 * asserting the two agree. Null column arrays (an analyzer's lazy list never
 * created), `isMultiSchema` false beside a two-entry `schemas` list (the flag
 * set only by an analyzer that can be skipped), and a merged profile inheriting
 * uninitialised superclass fields.
 *
 * <p>So the invariants are checked against every state that builds a profile
 * differently — analysed, skipped, failed, merged, CTE — because "holds for a
 * normal profile" is exactly the assumption each of those bugs satisfied.
 *
 * <p>Companion to {@code NullListDefaultsTest.mergedSqlProfileOverridesEveryInstanceGetter},
 * which guards the narrower "merged profile forgot a getter" case by reflection.
 */
public class SqlProfileInvariantsTest {

    private static final SchemaFixture FIXTURE = fixture();

    private static SchemaFixture fixture() {
        try {
            return SchemaFixture.fromSql("""
                create table folders (
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

    /** Every distinct way a profile gets built, by the path that builds it. */
    static Stream<Arguments> everyProfileShape() throws JSQLParserException {
        Map<String, SqlProfile> shapes = new LinkedHashMap<>();

        shapes.put("analyzed", FIXTURE.analyze(
            "SELECT `name` FROM `folders` WHERE `uuid` = ? GROUP BY `name`"));

        shapes.put("analyzed, joined", FIXTURE.analyze(
            "SELECT `f`.`name` FROM `folders` `f` JOIN `users` `u` ON `u`.`category_uuid` = `f`.`uuid`"));

        shapes.put("analyzed, cross-schema", FIXTURE.analyze(
            "SELECT `f`.`name` FROM `folders` `f` "
                + "JOIN `other_db`.`audit_log` `a` ON `a`.`category_uuid` = `f`.`uuid`"));

        // SelectColumnAnalyzer throws "missing schema": a bare column, no FROM
        shapes.put("analysis failed", FIXTURE.analyze("SELECT\n        NOW"));

        shapes.put("cte", FIXTURE.analyze("""
            WITH `owned` AS (
                SELECT `f`.`uuid`, `u`.`id`
                FROM `folders` `f`
                JOIN `users` `u` ON `u`.`category_uuid` = `f`.`uuid`
                WHERE `f`.`name` = ?
            )
            SELECT `uuid` FROM `owned`
            """));

        // the skip path: tables set by the regex pre-scan, no analyzer runs.
        // SchemaFixture cannot produce this — it always runs the analyzers.
        shapes.put("analysis skipped", skipped(List.of(
            TableReference.fromString("mysql.triggers"),
            TableReference.fromString("information_schema.triggers"))));

        shapes.put("analysis skipped, one schema", skipped(List.of(
            TableReference.fromString("mysql.triggers"))));

        shapes.put("analysis skipped, no tables", skipped(List.of()));

        SqlProfile a = FIXTURE.analyze("SELECT `name` FROM `folders` WHERE `uuid` = ?");
        SqlProfile b = FIXTURE.analyze("SELECT `name` FROM `folders` WHERE `uuid` = ?");
        shapes.put("merged", new MergedSqlProfile(List.of(a, b)));

        SqlProfile x = FIXTURE.analyze(
            "SELECT `f`.`name` FROM `folders` `f` "
                + "JOIN `other_db`.`audit_log` `a` ON `a`.`category_uuid` = `f`.`uuid`");
        shapes.put("merged, cross-schema", new MergedSqlProfile(List.of(x, x)));

        return shapes.entrySet().stream()
            .map(e -> Arguments.of(Named.of(e.getKey(), e.getValue())));
    }

    private static SqlProfile skipped(List<TableReference> tables) {
        return SqlProfile.newBuilder()
            .setId("skipped")
            .setDigest("skipped")
            .setNormalizedDigest("skipped")
            .setQuery("SELECT 1 FROM somewhere")
            .setType("SELECT")
            .setSchemaName("mysql")
            .setQueryActivityStatus(QueryActivityStatus.ACTIVE)
            .setRuntimeMetrics(null)
            .setTables(tables)
            .setAnalysisSkipped(true)
            .completeBuild("mysql");
    }

    /**
     * No list field is ever null.
     *
     * <p>Would have failed before the {@code MergedSqlProfile} fix: `merged`
     * returned null for selectColumns, groupByColumns and schemas, because the
     * class overrides getters one at a time and inherited the uninitialised
     * superclass field for the three it missed. ~5% of the corpus, and the SQL
     * view read `.length` off them.
     */
    @ParameterizedTest(name = "no null lists [{0}]")
    @MethodSource("everyProfileShape")
    public void listFieldsAreNeverNull(SqlProfile profile) {
        assertThat(profile.getTables()).as("tables").isNotNull();
        assertThat(profile.getJoinColumns()).as("joinColumns").isNotNull();
        assertThat(profile.getCriteriaColumns()).as("criteriaColumns").isNotNull();
        assertThat(profile.getSelectColumns()).as("selectColumns").isNotNull();
        assertThat(profile.getGroupByColumns()).as("groupByColumns").isNotNull();
        assertThat(profile.getSchemas()).as("schemas").isNotNull();
        assertThat(profile.getMismatchedJoins()).as("mismatchedJoins").isNotNull();
        assertThat(profile.getSourcedFiles()).as("sourcedFiles").isNotNull();
    }

    /**
     * More than one schema means multi-schema.
     *
     * <p>Would have failed before the {@code complete()} fix on the
     * `analysis skipped` shape: `schemas` is derived from the tables for every
     * profile, but the flag was set only by {@link SchemaAnalyzer}, which does
     * not run when analysis is skipped — so a skipped cross-schema statement
     * listed two schemas and claimed one. Live digest 37330a9e….
     */
    @ParameterizedTest(name = "schemas>1 implies isMultiSchema [{0}]")
    @MethodSource("everyProfileShape")
    public void moreThanOneSchemaMeansMultiSchema(SqlProfile profile) {
        if (profile.getSchemas().size() > 1) {
            assertThat(profile.getIsMultiSchema())
                .as("lists %s but does not report multi-schema", profile.getSchemas())
                .isTrue();
        }
    }

    /**
     * The converse: the flag must not claim more than the list shows.
     *
     * <p><b>Currently failing on the cross-schema shapes</b>, for the same
     * root cause as the coverage invariant below: the unqualified side of the
     * join never reaches `schemas`, so the list has one entry while
     * {@link SchemaAnalyzer} — which resolves unqualified tables against the
     * profile's schema — correctly sets the flag. Not violated anywhere in the
     * live corpus (0 of 3,608), because its cross-schema statements qualify
     * both sides.
     */
    @org.junit.jupiter.api.Disabled("real bug, pending decision — see WORK_TODO; same root cause as schemasCoversEveryTablesSchema")
    @ParameterizedTest(name = "isMultiSchema implies schemas>1 [{0}]")
    @MethodSource("everyProfileShape")
    public void multiSchemaMeansMoreThanOneSchema(SqlProfile profile) {
        if (profile.getIsMultiSchema()) {
            assertThat(profile.getSchemas())
                .as("reports multi-schema but lists %s", profile.getSchemas())
                .hasSizeGreaterThan(1);
        }
    }

    /**
     * <b>Currently failing — a real bug, reported not fixed.</b>
     *
     * <p>`complete()` calls {@code addSchema(table.getNormalizedSchemaName())}
     * on the *original* table references, and {@code addSchema} returns early
     * on null. The pass that applies {@code withDefaultSchema(defaultSchema)}
     * to those tables runs later in the same method. So a statement whose
     * tables were unqualified in the SQL — the common case — contributes
     * nothing to `schemas`, then has its tables filled in afterwards.
     *
     * <p>Live impact: 1,816 of 3,608 profiles (50%) list a table in a schema
     * their `schemas` list omits; most have an empty list entirely. The
     * likely fix is to derive `schemas` after the default-schema pass, or to
     * pass the default into {@code addSchema} — but that is a product change
     * and is pending a decision.
     */
    @org.junit.jupiter.api.Disabled("real bug, pending decision — see WORK_TODO; 50% of the live corpus violates this")
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

    /**
     * A profile always has the identity the rest of the app keys on. `digest`
     * and `normalizedDigest` are what merging, tagging and every link use.
     */
    @ParameterizedTest(name = "identity present [{0}]")
    @MethodSource("everyProfileShape")
    public void identityFieldsArePresent(SqlProfile profile) {
        assertThat(profile.getId()).as("id").isNotNull();
        assertThat(profile.getDigest()).as("digest").isNotNull();
        assertThat(profile.getNormalizedDigest()).as("normalizedDigest").isNotNull();
        assertThat(profile.getSchemaName()).as("schemaName").isNotNull();
    }

    /**
     * A failure reason and the failure flag travel together — the SQL view
     * gates its banner on both, so a reason without the flag renders nothing
     * and a flag without a reason renders an empty banner.
     */
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
