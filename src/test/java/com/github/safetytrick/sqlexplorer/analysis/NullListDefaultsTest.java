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
        assertThat(profile.getMismatchedJoins()).as("mismatchedJoins").isNotNull();
        assertThat(profile.getSchemas()).as("schemas").isNotNull();
        assertThat(profile.getSourcedFiles()).as("sourcedFiles").isNotNull();
    }

    /**
     * `SELECT NOW` — the live case. The parentheses are gone, so `NOW` parses as
     * a bare column with no FROM; SelectColumnAnalyzer asks the resolver for its
     * table, there are no candidates, and it throws "missing schema" before ever
     * creating the select-column list.
     */
    @Test
    public void aProfileWhoseAnalyzerThrewStillHasEmptyListsNotNulls() throws JSQLParserException {
        SqlProfile profile = FIXTURE.analyze("SELECT\n        NOW");

        assertThat(profile.getAnalysisFailed()).isTrue();
        assertThat(profile.getAnalysisFailureReason()).contains("SelectColumnAnalyzer");
        assertNoNullLists(profile);
        assertThat(profile.getSelectColumns()).isEmpty();
        assertThat(profile.getGroupByColumns()).isEmpty();
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
        assertThat(mergedProfile.getSchemas()).as("schemas").isNotNull();

        assertThat(mergedProfile.getSelectColumns()).isEqualTo(a.getSelectColumns());
        assertThat(mergedProfile.getGroupByColumns()).isEqualTo(a.getGroupByColumns());
        assertThat(mergedProfile.getSchemas()).isEqualTo(a.getSchemas());
    }
}
