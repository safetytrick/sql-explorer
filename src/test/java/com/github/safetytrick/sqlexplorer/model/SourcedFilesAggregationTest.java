package com.github.safetytrick.sqlexplorer.model;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `sourcedFiles` answers "which files contributed to this query", which is only
 * interesting once a query spans more than one of them.
 *
 * <p>{@link MergedSqlProfile} has always flat-mapped this across a merge group
 * correctly. What it flat-mapped was empty lists: the singular
 * {@code sourceFileName} written at ingest was never carried into the plural,
 * so all 3,608 live profiles had an empty list and the field looked like dead
 * weight rather than an unfinished seam.
 */
class SourcedFilesAggregationTest {

    @Test
    void collectsEveryFileThatContributedToAMergeGroup() {
        MergedSqlProfile merged = new MergedSqlProfile(List.of(
            profileFrom("monday.tsv"), profileFrom("tuesday.tsv"), profileFrom("monday.tsv")));

        assertThat(merged.getSourcedFiles())
            .containsExactly("monday.tsv", "tuesday.tsv", "monday.tsv");
    }

    /**
     * A live source is not a file, so it contributes no name — and an empty
     * list is the honest answer rather than a placeholder.
     */
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
