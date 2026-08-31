package com.github.safetytrick.sqlexplorer.evidence;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.MergedChild;
import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.RepeatedBlock;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.evidence.SqlEvidenceInputService.SqlRepositoryBuilder;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetConfig;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetSink;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetSqlRepository;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlInput;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Merge groups have to survive a reload, which is the whole reason the group
 * lives in an Avro field rather than in memory.
 *
 * <p>Two things used to be lost. A merged child's own repeat counts were never
 * computed — {@code attemptMerge} returned before the normalizer's repeat
 * metadata was recorded — so "this variant batched 3, that one batched 2" was
 * unanswerable. And children never reached parquet at all: the write happened
 * per row, *before* any child had merged in, so the representative was
 * serialized with an empty group and the children were skipped entirely.
 *
 * <p>Driven through {@link SqlRepositoryBuilder} rather than a full import.
 * {@code fromPerformanceSchema} submits rows to a work-stealing pool, and
 * which row wins the race to become the representative is not deterministic —
 * two rows can even both miss the {@code containsKey} check and each become a
 * representative, so an end-to-end version of this is flaky by construction.
 * The calls below are the exact sequence {@code processRow} makes.
 */
class MergeGroupPersistenceTest {

    private static final String NORMALIZED_DIGEST = "shared-normalized-digest";
    /** What the display text looks like once the repeats are collapsed. */
    private static final String COLLAPSED = "select `id` from `t` where `a` like ?";

    @TempDir
    Path tempDir;

    @Test
    void theGroupIsRecordedOnTheRepresentativeAndSurvivesParquet() throws IOException {
        Path parquetFile = tempDir.resolve("profiles.parquet");
        ParquetSink sink = sink(parquetFile);
        SqlRepositoryBuilder repository = new SqlRepositoryBuilder(new TestSource());

        // first row through: nothing to merge with, so it is the representative
        SqlProfile.Builder first = profile("digest-a", 100L, 3);
        assertThat(repository.attemptMerge(NORMALIZED_DIGEST, first)).isFalse();
        SqlProfile representative = first.completeBuild("tdb");
        repository.addToIndex(representative);

        // second row normalizes identically, so it folds in — carrying its own
        // repeat count, which is the difference the expander exists to show
        assertThat(repository.attemptMerge(NORMALIZED_DIGEST, profile("digest-b", 5L, 2))).isTrue();

        assertThat(representative.getRepeatedBlocks()).extracting(RepeatedBlock::getCount).containsExactly(3);
        assertThat(representative.getMergedChildren())
            .extracting(MergedChild::getDigest, MergedChild::getExecuted)
            .containsExactly(tuple("digest-b", 5L));

        MergedChild child = representative.getMergedChildren().getFirst();
        assertThat(child.getRepeatedBlocks()).extracting(RepeatedBlock::getCount).containsExactly(2);
        assertThat(child.getAverageTime()).isEqualTo(BigDecimal.ONE);
        // the child's offsets index into the representative's text — the group
        // shares a normalized form, so they line up
        RepeatedBlock childBlock = child.getRepeatedBlocks().getFirst();
        assertThat(representative.getQuery().substring(childBlock.getStart(), childBlock.getEnd()))
            .isEqualTo("`a` like ?");

        // and now the point: one row on disk, group intact
        repository.writeParquet(sink);

        List<SqlProfile> reloaded = sink.readAll(ParquetSqlRepository.latestCapture(parquetFile.toString()));
        assertThat(reloaded).hasSize(1);
        assertThat(reloaded.getFirst().getMergedChildren())
            .extracting(MergedChild::getDigest, MergedChild::getExecuted)
            .containsExactly(tuple("digest-b", 5L));
        assertThat(reloaded.getFirst().getMergedChildren().getFirst().getRepeatedBlocks())
            .extracting(RepeatedBlock::getCount)
            .containsExactly(2);
    }

    /** A merged child never gets a row of its own — it would double-count. */
    @Test
    void mergedChildrenDoNotGetTheirOwnParquetRow() throws IOException {
        Path parquetFile = tempDir.resolve("no-dupes.parquet");
        ParquetSink sink = sink(parquetFile);
        SqlRepositoryBuilder repository = new SqlRepositoryBuilder(new TestSource());

        SqlProfile.Builder first = profile("digest-a", 100L, 3);
        repository.attemptMerge(NORMALIZED_DIGEST, first);
        repository.addToIndex(first.completeBuild("tdb"));
        repository.attemptMerge(NORMALIZED_DIGEST, profile("digest-b", 5L, 2));
        repository.attemptMerge(NORMALIZED_DIGEST, profile("digest-c", 7L, 4));

        assertThat(repository.parquetRowCount()).isEqualTo(1);

        repository.writeParquet(sink);
        assertThat(sink.readAll(ParquetSqlRepository.latestCapture(parquetFile.toString()))).hasSize(1);
    }

    /** An unmerged query still owns exactly one row, and carries no group. */
    @Test
    void ungroupedQueriesAreWrittenOnceWithAnEmptyGroup() throws IOException {
        Path parquetFile = tempDir.resolve("single.parquet");
        ParquetSink sink = sink(parquetFile);
        SqlRepositoryBuilder repository = new SqlRepositoryBuilder(new TestSource());

        SqlProfile.Builder only = profile("digest-a", 100L, 3);
        repository.attemptMerge(NORMALIZED_DIGEST, only);
        repository.addToIndex(only.completeBuild("tdb"));

        repository.writeParquet(sink);

        List<SqlProfile> reloaded = sink.readAll(ParquetSqlRepository.latestCapture(parquetFile.toString()));
        assertThat(reloaded).hasSize(1);
        assertThat(reloaded.getFirst().getMergedChildren()).isEmpty();
    }

    /**
     * A profile as {@code processRow} hands it to the merge check: display text
     * and repeats already computed, which is the ordering change that makes a
     * child's counts recordable at all.
     */
    /**
     * The ordering invariant `index()` provides, pinned so a regression is loud.
     *
     * <p>`MergedSqlProfile` delegates almost everything to `first()`, and
     * `mergeByDigest` groups in list order — so "the representative is first in
     * the profile list" is load-bearing. It used not to be guaranteed: `index()`
     * published to the digest map outside the lock and added to the list inside
     * it, so a child could reach the list ahead of the representative it merged
     * into. Both publications now happen under one lock.
     *
     * <p>This is the failure signal for that. Without it, reopening the window
     * shows up as a flipped representative and a vanished "×N variants"
     * expander — a symptom far from its cause. Note the assertion is on order,
     * not on the lock: it pins what callers depend on rather than how it is
     * currently achieved.
     */
    @Test
    void theRepresentativeIsFirstInTheProfileList() {
        SqlRepositoryBuilder repository = new SqlRepositoryBuilder(new TestSource());

        SqlProfile.Builder first = profile("digest-a", 100L, 3);
        assertThat(repository.attemptMerge(NORMALIZED_DIGEST, first)).isFalse();
        SqlProfile representative = first.completeBuild("tdb");
        repository.addToIndex(representative);

        // a child folds into the representative and is never indexed itself,
        // which is what keeps the ordering true
        assertThat(repository.attemptMerge(NORMALIZED_DIGEST, profile("digest-b", 5L, 2))).isTrue();

        // an unrelated profile, indexed after, must not displace it
        SqlProfile other = profile("digest-c", 1L, 1)
            .setNormalizedDigest("a-different-digest").completeBuild("tdb");
        repository.addToIndex(other);

        assertThat(repository.profilesForTest())
            .as("the representative must be indexed before anything that merged into it")
            .first()
            .isSameAs(representative);
        assertThat(representative.getMergedChildren()).hasSize(1);
    }

    private static SqlProfile.Builder profile(String digest, long executed, int repeatCount) {
        return SqlProfile.newBuilder()
            .setId(digest)
            .setQuery(COLLAPSED)
            .setType("SELECT")
            .setSchemaName("tdb")
            .setLineNumber(1)
            .setNormalizedDigest(NORMALIZED_DIGEST)
            .setQueryActivityStatus(QueryActivityStatus.ACTIVE)
            .setRepeatedBlocks(List.of(new RepeatedBlock(
                COLLAPSED.indexOf("`a` like ?"),
                COLLAPSED.indexOf("`a` like ?") + "`a` like ?".length(),
                repeatCount)))
            .setRuntimeMetrics(metrics(digest, executed));
    }

    private static RuntimeMetrics metrics(String digest, long executed) {
        return RuntimeMetrics.newBuilder()
            .setFullTableScan(false)
            .setExecuted(executed)
            .setErrors(0).setWarnings(0)
            .setTotalTime(BigDecimal.TEN)
            .setMaxTime(BigDecimal.ONE)
            .setAverageTime(BigDecimal.ONE)
            .setRowsSent(1).setAverageRowsSent(1)
            .setRowsScanned(1).setAverageRowsScanned(1)
            .setTempTables(0).setTempDiskTables(0)
            .setRowsSorted(0).setSortMergePasses(0)
            .setDigest(digest)
            .setLastSeen(LocalDateTime.now())
            .setFirstSeen(LocalDateTime.now().minusDays(1))
            .setLockTime(BigDecimal.ZERO)
            .setRowsAffected(0).setAverageRowsAffected(0)
            .completeBuild();
    }

    private ParquetSink sink(Path parquetFile) throws IOException {
        ParquetConfig config = new ParquetConfig();
        config.setFilename(parquetFile.toString());
        return new ParquetSink(config);
    }

    private static final class TestSource implements SqlSource {
        @Override
        public Stream<SqlInput> getSql() {
            return Stream.empty();
        }

        @Override
        public Instant getCreatedOn() {
            return Instant.now();
        }

        @Override
        public String getSourceRef() {
            return "merge-group-test";
        }
    }
}
