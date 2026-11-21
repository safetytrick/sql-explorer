package com.github.safetytrick.sqlexplorer.model;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourcedFilesAggregationTest {

    @Test
    void collectsEveryFileThatContributedToAMergeGroup() {
        MergedSqlProfile merged = new MergedSqlProfile(List.of(
            profileFrom("monday.tsv"), profileFrom("tuesday.tsv"), profileFrom("monday.tsv")));

        assertThat(merged.getSourcedFiles())
            .containsExactly("monday.tsv", "tuesday.tsv", "monday.tsv");
    }

    @Test
    void isEmptyWhenNothingCameFromAFile() {
        MergedSqlProfile merged = new MergedSqlProfile(List.of(profileFrom(null), profileFrom(null)));

        assertThat(merged.getSourcedFiles()).isEmpty();
    }

    @Test
    void aSingleProfileCarriesItsOwnFile() {
        assertThat(profileFrom("monday.tsv").getSourcedFiles()).containsExactly("monday.tsv");
    }

    private static SqlProfile profileFrom(String file) {
        return SqlProfile.newBuilder()
            .setId("id-" + file)
            .setDigest("d")
            .setNormalizedDigest("d")
            .setQuery("select 1")
            .setType("SELECT")
            .setSchemaName("app")
            .setQueryActivityStatus(QueryActivityStatus.ACTIVE)
            .setRuntimeMetrics(null)
            .setSourceFileName(file)
            .setSourcedFiles(file == null ? List.of() : List.of(file))
            .build();
    }
}
