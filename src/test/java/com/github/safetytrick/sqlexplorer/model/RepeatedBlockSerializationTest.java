package com.github.safetytrick.sqlexplorer.model;

import java.math.BigDecimal;
import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.MergedChild;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.RepeatedBlock;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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

    @Test
    void profileSerializesItsMergedChildren() {
        String json = mapper.writeValueAsString(representativeWithGroup());

        assertThat(json)
            .contains("\"mergedChildren\":[{")
            .contains("\"digest\":\"child-digest\"")
            .contains("\"executed\":3")
            .doesNotContain("\"schema\"");
    }

    @Test
    void mergedProfileDescribesItsGroupFromItsMembers() {
        MergedSqlProfile merged =
            new MergedSqlProfile(List.of(profileWithRepeats(), memberWithMetrics()));

        assertThat(merged.getMergedChildren())
            .extracting(MergedChild::getDigest, MergedChild::getExecuted)
            .containsExactly(tuple("member-digest", 7L));
        assertThat(merged.getMergedChildren().getFirst().getRepeatedBlocks())
            .extracting(RepeatedBlock::getCount).containsExactly(4);
    }

    @Test
    void aStoredGroupOnTheRepresentativeIsNotReadBack() {
        MergedSqlProfile merged =
            new MergedSqlProfile(List.of(representativeWithGroup(), profileWithRepeats()));

        assertThat(merged.getMergedChildren())
            .extracting(MergedChild::getDigest)
            .doesNotContain("child-digest");
    }

    private static SqlProfile memberWithMetrics() {
        return SqlProfile.newBuilder()
            .setQuery("SELECT ? FROM t WHERE id IN (?)")
            .setType("SELECT")
            .setQueryActivityStatus(QueryActivityStatus.ACTIVE)
            .setRuntimeMetrics(RuntimeMetrics.newBuilder()
                .setFullTableScan(false)
                .setExecuted(7L)
                .setErrors(0).setWarnings(0)
                .setTotalTime(BigDecimal.TEN)
                .setMaxTime(BigDecimal.ONE)
                .setAverageTime(BigDecimal.ONE)
                .setRowsSent(1).setAverageRowsSent(1)
                .setRowsScanned(1).setAverageRowsScanned(1)
                .setTempTables(0).setTempDiskTables(0)
                .setRowsSorted(0).setSortMergePasses(0)
                .setDigest("member-digest")
                .setLastSeen(java.time.LocalDateTime.now())
                .setFirstSeen(java.time.LocalDateTime.now().minusDays(1))
                .setLockTime(BigDecimal.ZERO)
                .setRowsAffected(0).setAverageRowsAffected(0)
                .completeBuild())
            .setLineNumber(2)
            .setRepeatedBlocks(List.of(new RepeatedBlock(29, 30, 4)))
            .completeBuild("tdb");
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
