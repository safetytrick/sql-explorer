package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `schemas` and `isMultiSchema` disagree on a skipped profile.
 *
 * <p>They are filled by two different paths:
 *
 * <ul>
 *   <li>{@code schemas} — derived in {@code SqlProfileExtension.complete()} from
 *       the profile's table references. That runs for <em>every</em> profile,
 *       analysed or not.</li>
 *   <li>{@code isMultiSchema} — set only by {@link SchemaAnalyzer}, which does
 *       not run when analysis is skipped.</li>
 * </ul>
 *
 * <p>A statement whose tables are all in ignored schemas (`mysql`,
 * `information_schema`, `performance_schema`, `sys`, `percona`) is skipped by
 * {@code SqlEvidenceInputService} — but its tables are still extracted by the
 * regex pre-scan and still set on the builder. So `complete()` derives two
 * schemas from them while the flag stays at its default false.
 *
 * <p>Live example, digest 37330a9e…: a join across `mysql.triggers`,
 * `mysql.schemata` and `information_schema.triggers`, reporting
 * {@code schemas = [mysql, information_schema]} with
 * {@code isMultiSchema = false}.
 *
 * <p>Fixed by deriving the flag from the schemas list in {@code complete()},
 * alongside the loop that builds it — a promotion to true only, so it cannot
 * contradict {@link SchemaAnalyzer}, which sets true in exactly the cases where
 * the list has more than one entry.
 *
 * <p>These model the skip path directly. {@code SchemaFixture} cannot: it always
 * runs the analyzers, so it never produces the state the bug needs.
 */
public class SkippedMultiSchemaTest {

    /** A profile as the skip path leaves it: tables set, no analyzer run. */
    private static SqlProfile skippedProfileSpanningTwoSchemas() {
        return SqlProfile.newBuilder()
            .setId("37330a9e")
            .setDigest("37330a9e")
            .setNormalizedDigest("37330a9e")
            .setQuery("SELECT `sch`.`name` FROM `mysql`.`triggers` `trg` "
                + "JOIN `mysql`.`schemata` `sch` ON (`trg`.`schema_id` = `sch`.`id`) "
                + "LEFT JOIN `information_schema`.`triggers` `ist` ON (`sch`.`name` = `ist`.`TRIGGER_SCHEMA`)")
            .setType("SELECT")
            .setSchemaName("mysql")
            .setQueryActivityStatus(QueryActivityStatus.ACTIVE)
            .setRuntimeMetrics(null)
            // what the regex pre-scan extracts before the skip decision
            .setTables(List.of(
                TableReference.fromString("mysql.triggers"),
                TableReference.fromString("mysql.schemata"),
                TableReference.fromString("information_schema.triggers")))
            .setAnalysisSkipped(true)
            .completeBuild("mysql");
    }

    @Test
    public void skippedProfileSpanningTwoSchemasReportsMultiSchema() {
        SqlProfile profile = skippedProfileSpanningTwoSchemas();

        assertThat(profile.getSchemas())
            .as("complete() derives these from the tables, for every profile")
            .containsExactlyInAnyOrder("mysql", "information_schema");

        assertThat(profile.getIsMultiSchema())
            .as("two schemas listed, so the flag must agree — even though analysis was skipped")
            .isTrue();
    }

    /** The promotion must not fire for a statement inside one schema. */
    @Test
    public void skippedProfileInASingleSchemaDoesNotReportMultiSchema() {
        SqlProfile profile = SqlProfile.newBuilder()
            .setId("single")
            .setDigest("single")
            .setNormalizedDigest("single")
            .setQuery("SELECT `id` FROM `mysql`.`schemata`")
            .setType("SELECT")
            .setSchemaName("mysql")
            .setQueryActivityStatus(QueryActivityStatus.ACTIVE)
            .setRuntimeMetrics(null)
            .setTables(List.of(TableReference.fromString("mysql.schemata")))
            .setAnalysisSkipped(true)
            .completeBuild("mysql");

        assertThat(profile.getSchemas()).containsExactly("mysql");
        assertThat(profile.getIsMultiSchema()).isFalse();
    }

    /** A profile with no tables at all must not be promoted either. */
    @Test
    public void aProfileWithNoTablesDoesNotReportMultiSchema() {
        SqlProfile profile = SqlProfile.newBuilder()
            .setId("none")
            .setDigest("none")
            .setNormalizedDigest("none")
            .setQuery("SELECT 1")
            .setType("SELECT")
            .setSchemaName("mysql")
            .setQueryActivityStatus(QueryActivityStatus.ACTIVE)
            .setRuntimeMetrics(null)
            .setAnalysisSkipped(true)
            .completeBuild("mysql");

        assertThat(profile.getSchemas()).isEmpty();
        assertThat(profile.getIsMultiSchema()).isFalse();
    }

}
