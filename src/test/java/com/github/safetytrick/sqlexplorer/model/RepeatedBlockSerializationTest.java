package com.github.safetytrick.sqlexplorer.model;

import java.math.BigDecimal;
import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.MergedChild;
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

    /**
     * Same trap as {@link RepeatedBlock}: a new Avro record reaching the wire
     * without its mixin makes Jackson walk into the generated {@code Schema}
     * and abort mid-response, truncating everything after it.
     */
    @Test
    void mergedChildSerializesItsDigestRepeatsAndMetrics() {
        String json = mapper.writeValueAsString(new MergedChild(
            "abc123", List.of(new RepeatedBlock(29, 30, 4)), 17L, new BigDecimal("1.5")));

        assertThat(json)
            .contains("\"digest\":\"abc123\"")
            .contains("\"repeatedBlocks\":[{")
            .contains("\"count\":4")
            .contains("\"executed\":17")
            .contains("1.5")
            .doesNotContain("\"schema\"");
    }

    /** The array shape the workbench expander reads off a profile. */
    @Test
    void profileSerializesItsMergedChildren() {
        String json = mapper.writeValueAsString(representativeWithGroup());

        assertThat(json)
            .contains("\"mergedChildren\":[{")
            .contains("\"digest\":\"child-digest\"")
            .contains("\"executed\":3")
            .doesNotContain("\"schema\"");
    }

    /**
     * The group is recorded on the representative at import, so the wrapper has
     * to read through to it — its own Avro field is never populated.
     */
    @Test
    void mergedProfileExposesTheGroupRecordedOnTheRepresentative() {
        MergedSqlProfile merged =
            new MergedSqlProfile(List.of(representativeWithGroup(), profileWithRepeats()));

        assertThat(merged.getMergedChildren())
            .extracting(MergedChild::getDigest)
            .containsExactly("child-digest");
    }

    private static SqlProfile representativeWithGroup() {
        return SqlProfile.newBuilder()
            .setQuery("SELECT ? FROM t WHERE id IN (?)")
            .setType("SELECT")
            .setQueryActivityStatus(QueryActivityStatus.ACTIVE)
            .setRuntimeMetrics(null)
            .setLineNumber(1)
            .setRepeatedBlocks(List.of(new RepeatedBlock(29, 30, 10)))
            .setMergedChildren(List.of(new MergedChild(
                "child-digest", List.of(new RepeatedBlock(29, 30, 4)), 3L, BigDecimal.ONE)))
            .completeBuild("tdb");
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
