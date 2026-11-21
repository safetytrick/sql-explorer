package com.github.safetytrick.sqlexplorer.evidence;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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

class MergeGroupPersistenceTest {

    private static final String NORMALIZED_DIGEST = "shared-normalized-digest";
    private static final String COLLAPSED = "select `id` from `t` where `a` like ?";

    @TempDir
    Path tempDir;

    @Test
    void theGroupSurvivesParquetByBeingRebuiltFromItsMembers() throws IOException {
        Path parquetFile = tempDir.resolve("profiles.parquet");
        ParquetSink sink = sink(parquetFile);
        SqlRepositoryBuilder repository = new SqlRepositoryBuilder(new TestSource());

        SqlProfile.Builder first = profile("digest-a", 100L, 3);
        assertThat(repository.attemptMerge(NORMALIZED_DIGEST, first)).isFalse();
        SqlProfile representative = first.completeBuild("tdb");
        repository.addToIndex(representative);

        assertThat(repository.attemptMerge(NORMALIZED_DIGEST, profile("digest-b", 5L, 2))).isTrue();

        assertThat(representative.getRepeatedBlocks()).extracting(RepeatedBlock::getCount).containsExactly(3);

        repository.writeParquet(sink);
        List<SqlProfile> reloaded = sink.readAll(ParquetSqlRepository.latestCapture(parquetFile.toString()));
        assertThat(reloaded).hasSize(2);
        assertThat(reloaded).allSatisfy(row ->
            assertThat(row.getMergedChildren()).as("grouping is not persisted").isEmpty());

        SqlProfile group = new AnalyzedSqlRepository(new TestSource(),
            SqlImportStats.builder().build(), reloaded).getAllAnalyzedSql().findFirst().orElseThrow();

        assertThat(group.getMergedChildren())
            .extracting(MergedChild::getDigest, MergedChild::getExecuted)
            .containsExactly(tuple("digest-b", 5L));
        MergedChild child = group.getMergedChildren().getFirst();
        assertThat(child.getRepeatedBlocks()).extracting(RepeatedBlock::getCount).containsExactly(2);
        assertThat(child.getAverageTime()).isEqualTo(BigDecimal.ONE);
        RepeatedBlock childBlock = child.getRepeatedBlocks().getFirst();
        assertThat(group.getQuery().substring(childBlock.getStart(), childBlock.getEnd()))
            .isEqualTo("`a` like ?");
    }

    @Test
    void everyMemberOfAGroupGetsItsOwnParquetRow() throws IOException {
        Path parquetFile = tempDir.resolve("members.parquet");
        ParquetSink sink = sink(parquetFile);
        SqlRepositoryBuilder repository = new SqlRepositoryBuilder(new TestSource());

        SqlProfile.Builder first = profile("digest-a", 100L, 3);
        repository.attemptMerge(NORMALIZED_DIGEST, first);
        repository.addToIndex(first.completeBuild("tdb"));
        repository.attemptMerge(NORMALIZED_DIGEST, profile("digest-b", 5L, 2));
        repository.attemptMerge(NORMALIZED_DIGEST, profile("digest-c", 7L, 4));

        assertThat(repository.parquetRowCount()).isEqualTo(3);

        repository.writeParquet(sink);
        List<SqlProfile> reloaded = sink.readAll(ParquetSqlRepository.latestCapture(parquetFile.toString()));
        assertThat(reloaded).hasSize(3);

        assertThat(new AnalyzedSqlRepository(new TestSource(), SqlImportStats.builder().build(), reloaded)
            .getAllAnalyzedSql().count()).isEqualTo(1);
    }

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

    @Test
    void theRepresentativeIsFirstInTheProfileList() {
        SqlRepositoryBuilder repository = new SqlRepositoryBuilder(new TestSource());

        SqlProfile.Builder first = profile("digest-a", 100L, 3);
        assertThat(repository.attemptMerge(NORMALIZED_DIGEST, first)).isFalse();
        SqlProfile representative = first.completeBuild("tdb");
        repository.addToIndex(representative);

        assertThat(repository.attemptMerge(NORMALIZED_DIGEST, profile("digest-b", 5L, 2))).isTrue();

        SqlProfile other = profile("digest-c", 1L, 1)
            .setNormalizedDigest("a-different-digest").completeBuild("tdb");
        repository.addToIndex(other);

        assertThat(repository.profilesForTest())
            .as("the representative must be indexed before anything that merged into it")
            .first()
            .isSameAs(representative);
        assertThat(repository.profilesForTest()).hasSize(3);
    }

    @Test
    void aGroupReportsTheSameMetricsAfterARoundTrip() throws IOException {
        Path parquetFile = tempDir.resolve("round-trip.parquet");
        ParquetSink sink = sink(parquetFile);
        SqlRepositoryBuilder repository = new SqlRepositoryBuilder(new TestSource());

        SqlProfile.Builder first = profile("digest-a", 100L, 3);
        repository.attemptMerge(NORMALIZED_DIGEST, first);
        repository.addToIndex(first.completeBuild("tdb"));
        repository.attemptMerge(NORMALIZED_DIGEST, profile("digest-b", 5L, 2));
        repository.attemptMerge(NORMALIZED_DIGEST, profile("digest-c", 7L, 4));

        SqlProfile before = new AnalyzedSqlRepository(new TestSource(), SqlImportStats.builder().build(),
            repository.profilesForTest()).getAllAnalyzedSql().findFirst().orElseThrow();

        repository.writeParquet(sink);
        List<SqlProfile> reloaded = sink.readAll(ParquetSqlRepository.latestCapture(parquetFile.toString()));
        SqlProfile after = new AnalyzedSqlRepository(new TestSource(), SqlImportStats.builder().build(),
            reloaded).getAllAnalyzedSql().findFirst().orElseThrow();

        assertThat(after.getRuntimeMetrics().getExecuted())
            .as("the group's executions, not the representative's")
            .isEqualTo(before.getRuntimeMetrics().getExecuted())
            .isEqualTo(112L);
        assertThat(after.getRuntimeMetrics().getTotalTime())
            .isEqualByComparingTo(before.getRuntimeMetrics().getTotalTime());
        assertThat(after.getRuntimeMetrics().getFirstSeen())
            .isEqualTo(before.getRuntimeMetrics().getFirstSeen().truncatedTo(ChronoUnit.MILLIS));
        assertThat(after.getRuntimeMetrics().getLastSeen())
            .isEqualTo(before.getRuntimeMetrics().getLastSeen().truncatedTo(ChronoUnit.MILLIS));
        assertThat(after.getMergedChildren()).hasSameSizeAs(before.getMergedChildren());
        assertThat(after.getNormalizedDigest()).isEqualTo(before.getNormalizedDigest());
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
