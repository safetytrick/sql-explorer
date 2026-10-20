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

        // The live shape, and the reason the skip path mis-stamps at all: the
        // skip is decided *from the tables* (every one in an ignored schema),
        // so a statement reading another schema from this session is exactly
        // the statement no analyzer sees. Session `mysql`, reads
        // `information_schema` — digest 973d4bfe….
        shapes.put("analysis skipped, another schema", skipped("mysql", List.of(
            TableReference.fromString("information_schema.tables"))));

        SqlProfile a = FIXTURE.analyze("SELECT `name` FROM `folders` WHERE `uuid` = ?");
        SqlProfile b = FIXTURE.analyze("SELECT `name` FROM `folders` WHERE `uuid` = ?");
        shapes.put("merged", new MergedSqlProfile(List.of(a, b)));

        // A merge group that never passed through `attemptMerge`: the skip
        // path sets no normalized digest, so `complete()` digests the query
        // text and two identical skipped statements group in `mergeByDigest`
        // all the same. Distinct from the shape above — its members were built
        // without an analyzer ever running.
        shapes.put("merged, skipped", new MergedSqlProfile(List.of(
            skipped(List.of(TableReference.fromString("mysql.triggers"))),
            skipped(List.of(TableReference.fromString("mysql.triggers"))))));

        SqlProfile x = FIXTURE.analyze(
            "SELECT `f`.`name` FROM `folders` `f` "
                + "JOIN `other_db`.`audit_log` `a` ON `a`.`category_uuid` = `f`.`uuid`");
        shapes.put("merged, cross-schema", new MergedSqlProfile(List.of(x, x)));

        return shapes.entrySet().stream()
            .map(e -> Arguments.of(Named.of(e.getKey(), e.getValue())));
    }

    private static SqlProfile skipped(List<TableReference> tables) {
        return skipped("mysql", tables);
    }

    /** {@code sessionSchema} is what ingest stamps: the row's default database. */
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
        assertThat(profile.getIndexHints()).as("indexHints").isNotNull();
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
     * <p>Used to fail on the cross-schema shapes, same root cause as the
     * coverage invariant: the unqualified side of the join never reached
     * `schemas`, so the list had one entry while {@link SchemaAnalyzer} —
     * which resolves unqualified tables against the profile's schema —
     * correctly set the flag.
     */
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
     * A skipped statement is scoped to its schema too.
     *
     * <p>The skip path never reaches {@code NormalizedQueryDigest}: it has no
     * normalized form, so {@code complete()} derives `normalizedDigest` from
     * the MySQL digest instead — which performance_schema computes from
     * statement text alone and is therefore identical in every schema. Left
     * unscoped, `SHOW INDEXES FROM tokens` run in `msg` and in `app_other` was one
     * profile that reported one of the two, by race. Live digest 057140cc….
     */
    @Test
    public void aSkippedStatementDigestsPerSchema() {
        SqlProfile inMsg = skippedWithMysqlDigest("msg");
        SqlProfile inUgg = skippedWithMysqlDigest("app_other");

        assertThat(inMsg.getNormalizedDigest())
            .as("same text, different schema, must not be one profile")
            .isNotEqualTo(inUgg.getNormalizedDigest());
        assertThat(inMsg.getDigest()).isEqualTo(inMsg.getNormalizedDigest());

        assertThat(skippedWithMysqlDigest("msg").getNormalizedDigest())
            .as("same text, same schema, still one profile")
            .isEqualTo(inMsg.getNormalizedDigest());
    }

    /**
     * The skip path as ingest builds it: MySQL's digest present,
     * {@code normalizedDigest} absent because no normalized form was ever
     * computed. Both statements share the MySQL digest — that is the point,
     * performance_schema derives it from text alone.
     */
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

    /**
     * One schema means `schemaName` names it.
     *
     * <p>The field means *the* schema, and when the list has exactly one entry
     * there is no room for it to mean anything else. It used to: ingest stamps
     * the performance-schema row's session database (or `"dual"`), and only
     * {@link SchemaAnalyzer} collapsed it onto the sole schema — and the skip
     * decision is made from the tables, so a query reading
     * `information_schema` from a `mysql` session was both skipped and
     * mis-stamped. 127 of 3,608 live profiles, every one of them skipped.
     *
     * <p>No assertion for the multi-schema case on purpose: a statement
     * spanning schemas has no single answer, keeps the session value, and says
     * so through {@code isMultiSchema}.
     */
    @ParameterizedTest(name = "one schema names itself [{0}]")
    @MethodSource("everyProfileShape")
    public void oneSchemaMeansSchemaNameIsThatSchema(SqlProfile profile) {
        if (profile.getSchemas().size() == 1) {
            assertThat(profile.getSchemaName())
                .as("lists only %s but calls itself %s", profile.getSchemas(), profile.getSchemaName())
                .isEqualTo(profile.getSchemas().getFirst());
        }
    }

    /**
     * `schemas` covers every schema named in `tables`.
     *
     * <p>`complete()` derives the list from the table references, so the two
     * cannot legitimately disagree. It used to: the derivation ran on the
     * original refs, where an unqualified table has a null schema that
     * {@code addSchema} skips, and the {@code withDefaultSchema} pass that
     * fills those in ran later in the same method. 1,816 of 3,608 live
     * profiles (50%) listed a table in a schema their `schemas` omitted —
     * `tables = [search.heartbeat]` with `schemas = []`. Deriving after that
     * pass fixed it.
     */
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
     * Every getter answers without throwing.
     *
     * <p>Weaker than the invariants above and deliberately so: it is the net
     * under them. The interesting profile shapes are the ones nothing reads in
     * a test, and {@link MergedSqlProfile} computes rather than delegates for
     * several fields, so a getter can fail on its own. {@code getLineNumber}
     * did: {@code lineNumber} is {@code int?}, the merge unboxed it inside
     * {@code mapToInt}, and any member without one — everything not built by
     * the performance-schema ingest — made constructing the group throw NPE
     * instead of returning a wrong value.
     */
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
