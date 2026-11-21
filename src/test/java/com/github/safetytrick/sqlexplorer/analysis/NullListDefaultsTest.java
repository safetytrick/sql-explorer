package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.model.MergedSqlProfile;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class NullListDefaultsTest {

    private static final SchemaFixture FIXTURE = fixture();

    private static SchemaFixture fixture() {
        try {
            return SchemaFixture.fromSql("""
                create table category (
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

    @Test
    public void aColumnWithNoCandidateTablesResolvesToNothingRatherThanFailing() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze("SELECT\n        NOW");

        assertThat(profile.getAnalysisFailed()).isFalse();
        assertThat(profile.getAnalysisFailureReason()).isNull();
        assertNoNullLists(profile);
    }

    @Test
    public void aStatementWithNoGroupByHasAnEmptyGroupByList() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze("SELECT `name` FROM `category` WHERE `uuid` = ?");

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

    @Test
    public void aMergedProfileReportsItsRepresentativesColumnsNotNulls() throws JSQLParserException {
        SqlProfile a = FIXTURE.analyze("SELECT `name` FROM `category` WHERE `uuid` = ?");
        SqlProfile b = FIXTURE.analyze("SELECT `name` FROM `category` WHERE `uuid` = ?");

        SqlProfile mergedProfile = new MergedSqlProfile(List.of(a, b));

        assertThat(mergedProfile.getSelectColumns()).as("selectColumns").isNotNull();
        assertThat(mergedProfile.getGroupByColumns()).as("groupByColumns").isNotNull();
        assertThat(mergedProfile.getIndexHints()).as("indexHints").isNotNull();
        assertThat(mergedProfile.getSchemas()).as("schemas").isNotNull();

        assertThat(mergedProfile.getSelectColumns()).isEqualTo(a.getSelectColumns());
        assertThat(mergedProfile.getGroupByColumns()).isEqualTo(a.getGroupByColumns());
        assertThat(mergedProfile.getSchemas()).isEqualTo(a.getSchemas());
    }

    @Test
    public void aMergedProfileSpanningSchemasReportsMultiSchema() throws JSQLParserException {
        SqlProfile a = FIXTURE.analyze(
            "SELECT `f`.`name` FROM `category` `f` JOIN `other_db`.`audit_log` `a` ON `a`.`category_uuid` = `f`.`uuid`");
        SqlProfile b = FIXTURE.analyze(
            "SELECT `f`.`name` FROM `category` `f` JOIN `other_db`.`audit_log` `a` ON `a`.`category_uuid` = `f`.`uuid`");

        assertThat(a.getIsMultiSchema()).as("the representative itself").isTrue();
        assertThat(new MergedSqlProfile(List.of(a, b)).getIsMultiSchema()).isTrue();
    }

    @Test
    public void aMergedProfileWithinOneSchemaDoesNotReportMultiSchema() throws JSQLParserException {
        SqlProfile a = FIXTURE.analyze("SELECT `name` FROM `category` WHERE `uuid` = ?");
        SqlProfile b = FIXTURE.analyze("SELECT `name` FROM `category` WHERE `uuid` = ?");

        assertThat(new MergedSqlProfile(List.of(a, b)).getIsMultiSchema()).isFalse();
    }

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
