package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class SkippedMultiSchemaTest {

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
