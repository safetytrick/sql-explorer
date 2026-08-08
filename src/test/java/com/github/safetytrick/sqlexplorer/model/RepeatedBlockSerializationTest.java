package com.github.safetytrick.sqlexplorer.model;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.RepeatedBlock;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wire contract of the collapsed-repeat badges: the queries workbench
 * reads {@code start}/{@code end}/{@code count} off every entry of
 * {@code repeatedBlocks}. Avro's generated {@code getSchema()} is a bean getter,
 * so without the mixin Jackson walks into the Avro {@code Schema} and blows up
 * mid-response ("Not an array: {...}"), truncating the whole payload — hence the
 * {@code schema} assertion. Uses the app's module, not a bare mapper.
 */
class RepeatedBlockSerializationTest {

    private final JsonMapper mapper = JsonMapper.builder()
        .addModule(new SpecificRecordBaseSerde().getSpecificRecordBaseModule())
        .build();

    @Test
    void repeatedBlockSerializesItsOffsetsAndCount() {
        String json = mapper.writeValueAsString(new RepeatedBlock(12, 48, 3));

        assertThat(json)
            .contains("\"start\":12")
            .contains("\"end\":48")
            .contains("\"count\":3")
            .doesNotContain("\"schema\"");
    }

    @Test
    void profileSerializesItsRepeatedBlocks() {
        String json = mapper.writeValueAsString(profileWithRepeats());

        assertThat(json)
            .contains("\"repeatedBlocks\":[{")
            .contains("\"start\":29")
            .contains("\"end\":30")
            .contains("\"count\":10")
            .doesNotContain("\"schema\"");
    }

    /**
     * A digest group keeps the first member's normalized query text, and the
     * offsets index into that exact string — so the repeats have to come from
     * the same member. Later members merged at import carry the raw text and no
     * repeats, so without the override every merged query lost its badges.
     */
    @Test
    void mergedProfileKeepsTheRepeatsOfTheQueryItShows() {
        SqlProfile mergedAtImport = SqlProfile.newBuilder()
            .setQuery("SELECT ? FROM t WHERE id IN (?, ?)")
            .setType("SELECT")
            .setQueryActivityStatus(QueryActivityStatus.ACTIVE)
            .setRuntimeMetrics(null)
            .setLineNumber(1)
            .completeBuild("tdb");

        MergedSqlProfile merged = new MergedSqlProfile(List.of(profileWithRepeats(), mergedAtImport));

        assertThat(merged.getQuery()).isEqualTo(profileWithRepeats().getQuery());
        assertThat(merged.getRepeatedBlocks())
            .containsExactly(new RepeatedBlock(29, 30, 10));
    }

    private static SqlProfile profileWithRepeats() {
        return SqlProfile.newBuilder()
            .setQuery("SELECT ? FROM t WHERE id IN (?)")
            .setType("SELECT")
            .setQueryActivityStatus(QueryActivityStatus.ACTIVE)
            .setRuntimeMetrics(null)
            .setLineNumber(1)
            .setRepeatedBlocks(List.of(new RepeatedBlock(29, 30, 10)))
            .completeBuild("tdb");
    }
}
