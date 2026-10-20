package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.model.MergedSqlProfile;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * List fields must never be null on a built profile.
 *
 * <p>The schema declares them {@code = []}, but a field no analyzer populated
 * was arriving null: `setJoinColumns` and `setMismatchedJoins` are called
 * unconditionally, while `selectColumn`, `groupByColumn` and `addSchema`
 * create their list on first insert — so a statement with none of those, or an
 * analyzer that threw before finding one, left null behind.
 *
 * <p>~5% of the corpus was in that state, and the SQL view read `.length` off
 * those fields, throwing during change detection and blanking most of the page.
 *
 * <p>The builder path was never the culprit — it produces empty lists already.
 * {@link MergedSqlProfile} was: it overrides a getter at a time and silently
 * inherits whatever it forgets, and it is constructed with {@code new} rather
 * than through the Avro builder, so the three getters it did not override
 * returned the uninitialised superclass field.
 */
public class NullListDefaultsTest {

    private static final SchemaFixture FIXTURE = fixture();

    private static SchemaFixture fixture() {
        try {
            return SchemaFixture.fromSql("""
                create table folders (
                    uuid varchar(36) not null,
                    name varchar(255),
                    primary key (uuid)
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

    private static void assertNoNullLists(SqlProfile profile) {
        assertThat(profile.getTables()).as("tables").isNotNull();
        assertThat(profile.getJoinColumns()).as("joinColumns").isNotNull();
        assertThat(profile.getCriteriaColumns()).as("criteriaColumns").isNotNull();
        assertThat(profile.getSelectColumns()).as("selectColumns").isNotNull();
        assertThat(profile.getGroupByColumns()).as("groupByColumns").isNotNull();
        assertThat(profile.getIndexHints()).as("indexHints").isNotNull();
        assertThat(profile.getMismatchedJoins()).as("mismatchedJoins").isNotNull();
        assertThat(profile.getSchemas()).as("schemas").isNotNull();
        assertThat(profile.getSourcedFiles()).as("sourcedFiles").isNotNull();
    }

    /**
     * `SELECT NOW` — the live case, and it no longer fails.
     *
     * <p>MySQL's digest canonicalises the `CURRENT_TIMESTAMP` keyword to its
     * synonym `NOW`, with no parens because there were none to keep, so
     * jsqlparser reads it as a bare column with no FROM. This test used to
     * assert that the analysis *failed* — the resolver threw "missing schema"
     * when asked which table a column with no candidate tables belonged to,
     * and it was the only analyzer-thrown failure in the whole corpus.
     *
     * <p>A column with no candidates has no table, which is an answer rather
     * than an error; the resolver now returns unresolved and the profile
     * completes. The assertion is inverted deliberately, not relaxed.
     */
    @Test
    public void aColumnWithNoCandidateTablesResolvesToNothingRatherThanFailing() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze("SELECT\n        NOW");

        assertThat(profile.getAnalysisFailed()).isFalse();
        assertThat(profile.getAnalysisFailureReason()).isNull();
        assertNoNullLists(profile);
    }

    @Test
    public void aStatementWithNoGroupByHasAnEmptyGroupByList() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze("SELECT `name` FROM `folders` WHERE `uuid` = ?");

        assertThat(profile.getAnalysisFailed()).isFalse();
        assertNoNullLists(profile);
        assertThat(profile.getGroupByColumns()).isEmpty();
        assertThat(profile.getSelectColumns()).isNotEmpty();
    }

    @Test
    public void aStatementThatTouchesNothingStillHasEveryListPresent() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze("SELECT 1");

        assertNoNullLists(profile);
    }

    /**
     * The actual bug. A merge group of two profiles must report the
     * representative's columns, not the superclass's uninitialised nulls.
     */
    @Test
    public void aMergedProfileReportsItsRepresentativesColumnsNotNulls() throws JSQLParserException {
        SqlProfile a = FIXTURE.analyze("SELECT `name` FROM `folders` WHERE `uuid` = ?");
        SqlProfile b = FIXTURE.analyze("SELECT `name` FROM `folders` WHERE `uuid` = ?");

        SqlProfile mergedProfile = new MergedSqlProfile(List.of(a, b));

        assertThat(mergedProfile.getSelectColumns()).as("selectColumns").isNotNull();
        assertThat(mergedProfile.getGroupByColumns()).as("groupByColumns").isNotNull();
        assertThat(mergedProfile.getIndexHints()).as("indexHints").isNotNull();
        assertThat(mergedProfile.getSchemas()).as("schemas").isNotNull();

        assertThat(mergedProfile.getSelectColumns()).isEqualTo(a.getSelectColumns());
        assertThat(mergedProfile.getGroupByColumns()).isEqualTo(a.getGroupByColumns());
        assertThat(mergedProfile.getSchemas()).isEqualTo(a.getSchemas());
    }

    /**
     * `isMultiSchema` was the same miss as the three list getters: not
     * overridden, so a merged profile returned the uninitialised primitive
     * `false` and never matched the multiSchema filter. Delegated to the
     * representative, because the flag describes the statement's table
     * references and every member of a group is the same statement shape.
     */
    @Test
    public void aMergedProfileSpanningSchemasReportsMultiSchema() throws JSQLParserException {
        SqlProfile a = FIXTURE.analyze(
            "SELECT `f`.`name` FROM `folders` `f` JOIN `other_db`.`audit_log` `a` ON `a`.`category_uuid` = `f`.`uuid`");
        SqlProfile b = FIXTURE.analyze(
            "SELECT `f`.`name` FROM `folders` `f` JOIN `other_db`.`audit_log` `a` ON `a`.`category_uuid` = `f`.`uuid`");

        assertThat(a.getIsMultiSchema()).as("the representative itself").isTrue();
        assertThat(new MergedSqlProfile(List.of(a, b)).getIsMultiSchema()).isTrue();
    }

    @Test
    public void aMergedProfileWithinOneSchemaDoesNotReportMultiSchema() throws JSQLParserException {
        SqlProfile a = FIXTURE.analyze("SELECT `name` FROM `folders` WHERE `uuid` = ?");
        SqlProfile b = FIXTURE.analyze("SELECT `name` FROM `folders` WHERE `uuid` = ?");

        assertThat(new MergedSqlProfile(List.of(a, b)).getIsMultiSchema()).isFalse();
    }

    /**
     * The class delegates getter by getter, so anything it forgets silently
     * returns the uninitialised superclass field. This walks the reflection
     * surface so the next omission fails here rather than in production.
     */
    @Test
    public void mergedSqlProfileOverridesEveryInstanceGetter() {
        List<String> missing = java.util.Arrays.stream(SqlProfile.class.getDeclaredMethods())
            .filter(m -> m.getName().startsWith("get"))
            .filter(m -> m.getParameterCount() == 0)
            .filter(m -> !java.lang.reflect.Modifier.isStatic(m.getModifiers()))
            .map(java.lang.reflect.Method::getName)
            .filter(name -> !name.equals("getSchema") && !name.equals("getSpecificData")
                         && !name.equals("getConversion"))
            .distinct()
            .filter(name -> {
                try {
                    return MergedSqlProfile.class.getDeclaredMethod(name) == null;
                } catch (NoSuchMethodException e) {
                    return true;
                }
            })
            .sorted()
            .toList();

        assertThat(missing)
            .as("MergedSqlProfile must override every SqlProfile getter; these fall through "
                + "to the uninitialised superclass field")
            .isEmpty();
    }
}
