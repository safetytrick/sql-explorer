package com.github.safetytrick.sqlexplorer.evidence.sink;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One file per capture, and the corpus is the newest of them.
 *
 * <p>Overwriting a single file destroyed the only record that an earlier
 * window had been observed, which is what a Performance Schema reset looks
 * like from the outside. Keeping the files makes the gap visible; reading only
 * the newest keeps the corpus correct, because captures overlap and their
 * union would double-count (sqlx-sy5).
 */
class CaptureFileLayoutTest {

    @TempDir
    Path directory;

    private static CaptureContext capturedAt(String instant) {
        return new CaptureContext(CaptureContext.Origin.PARQUET_COPY, "primary",
            Instant.parse(instant), 1, null, null, Map.of());
    }

    private static SqlProfile profile() {
        return SqlProfile.newBuilder()
            .setQuery("select 1").setType("SELECT")
            .setDigest("d").setNormalizedDigest("d").setSchemaName("app")
            .setQueryActivityStatus(QueryActivityStatus.ACTIVE)
            .setRuntimeMetrics(null)
            .completeBuild("app");
    }

    private String base() {
        return directory.resolve("sql-repository.parquet").toString();
    }

    /**
     * A real, sealed capture. Empty placeholder files will not do any more:
     * {@link ParquetSqlRepository#latestCapture} answers the newest capture
     * that can actually be read, because the newest <em>name</em> is normally
     * the running process's own file, still open and footerless (sqlx-sy5).
     */
    private void writeCapture(String instant) throws IOException {
        ParquetConfig config = new ParquetConfig();
        config.setFilename(base());
        new ParquetSink(config).write(capturedAt(instant), List.of(profile()));
    }

    @Test
    void aCaptureIsWrittenBesideTheConfiguredNameRatherThanOverIt() {
        Path file = ParquetSink.fileFor(base(), capturedAt("2026-08-30T02:51:36Z"));

        assertThat(String.valueOf(file.getFileName())).isEqualTo("sql-repository-primary-20260830T025136Z.parquet");
        assertThat(file.getParent()).isEqualTo(directory);
    }

    /** Two captures must not collide, or the second still destroys the first. */
    @Test
    void twoCapturesGetTwoFiles() {
        Path first = ParquetSink.fileFor(base(), capturedAt("2026-08-30T02:51:36Z"));
        Path second = ParquetSink.fileFor(base(), capturedAt("2026-08-31T09:14:02Z"));

        assertThat(first).isNotEqualTo(second);
    }

    /** Chronological order has to survive as name order, or "latest" needs parsing. */
    @Test
    void theStampSortsChronologically() {
        String earlier = String.valueOf(ParquetSink.fileFor(base(), capturedAt("2026-08-30T02:51:36Z")).getFileName());
        String later = String.valueOf(ParquetSink.fileFor(base(), capturedAt("2026-09-01T00:00:00Z")).getFileName());

        assertThat(earlier.compareTo(later)).isNegative();
    }

    @Test
    void aWriteWithNoCaptureInstantKeepsTheConfiguredName() {
        assertThat(ParquetSink.fileFor(base(), null).getFileName().toString())
            .isEqualTo("sql-repository.parquet");
    }

    // ── what the corpus reads ──

    @Test
    void theCorpusIsTheNewestCapture() throws IOException {
        writeCapture("2026-08-30T02:51:36Z");
        writeCapture("2026-09-01T00:00:00Z");
        writeCapture("2026-08-15T12:00:00Z");

        assertThat(ParquetSqlRepository.latestCapture(base()).getFileName().toString())
            .isEqualTo("sql-repository-primary-20260901T000000Z.parquet");
    }

    /** The single-file layout that existed before this must still load. */
    @Test
    void fallsBackToTheLegacySingleFileWhenNoCaptureIsStamped() throws IOException {
        Files.createFile(directory.resolve("sql-repository.parquet"));

        assertThat(ParquetSqlRepository.latestCapture(base()).getFileName().toString())
            .isEqualTo("sql-repository.parquet");
    }

    /** A stamped capture wins over the legacy file, which is the older data. */
    @Test
    void aStampedCaptureIsPreferredOverTheLegacyFile() throws IOException {
        Files.createFile(directory.resolve("sql-repository.parquet"));
        writeCapture("2026-09-01T00:00:00Z");

        assertThat(ParquetSqlRepository.latestCapture(base()).getFileName().toString())
            .isEqualTo("sql-repository-primary-20260901T000000Z.parquet");
    }

    /**
     * Unrelated files in the directory must not be mistaken for captures. This
     * is the naming question, so it asks {@code captures} — the one owner of
     * what a capture file is called — rather than inferring it from which file
     * the corpus happened to resolve to.
     */
    @Test
    void ignoresFilesThatAreNotCapturesOfThisBase() throws IOException {
        Files.createFile(directory.resolve("something-else-primary-20270101T000000Z.parquet"));
        Files.createFile(directory.resolve("sql-repository-primary-20260830T025136Z.parquet"));

        assertThat(ParquetSqlRepository.captures(base()))
            .extracting(path -> String.valueOf(path.getFileName()))
            .containsExactly("sql-repository-primary-20260830T025136Z.parquet");
    }

    /**
     * The capture an import is still writing has no footer, and must not be
     * served as the corpus — it would read as an empty environment for the
     * whole life of the process.
     */
    @Test
    void anInProgressCaptureIsNotYetTheCorpus() throws IOException {
        writeCapture("2026-08-30T02:51:36Z");
        Files.createFile(directory.resolve("sql-repository-primary-20260901T000000Z.parquet"));

        assertThat(ParquetSqlRepository.latestCapture(base()).getFileName().toString())
            .isEqualTo("sql-repository-primary-20260830T025136Z.parquet");
    }

    // ── per source ──

    /**
     * "The newest capture" and "the newest capture OF THIS SOURCE" are
     * different questions once two sources are loaded, and the second is
     * almost always the one meant. Asking the first returns whichever source
     * happened to be captured last — which is the shape of the bug that made
     * captures per-source (sqlx-fx2).
     */
    @Test
    void aSourceFindsItsOwnLatestCaptureRatherThanTheNewestOverall() throws IOException {
        ParquetConfig config = new ParquetConfig();
        config.setFilename(base());
        ParquetSink sink = new ParquetSink(config);

        sink.write(capturedAt("2026-08-30T02:51:36Z"), List.of(profile()));
        sink.write(new CaptureContext(CaptureContext.Origin.PERFORMANCE_SCHEMA, "replica",
            Instant.parse("2026-09-01T00:00:00Z"), 1, null, null, Map.of()), List.of(profile()));

        assertThat(ParquetSqlRepository.latestCapture(base()).getFileName())
            .hasToString("sql-repository-replica-20260901T000000Z.parquet");
        assertThat(ParquetSqlRepository.latestCaptureOf(base(), "primary").getFileName())
            .hasToString("sql-repository-primary-20260830T025136Z.parquet");
        assertThat(ParquetSqlRepository.capturesOf(base(), "primary")).hasSize(1);
    }

    /** A source that has never been captured has no capture, not someone else's. */
    @Test
    void anUncapturedSourceResolvesToNothing() throws IOException {
        ParquetConfig config = new ParquetConfig();
        config.setFilename(base());
        new ParquetSink(config).write(capturedAt("2026-08-30T02:51:36Z"), List.of(profile()));

        assertThat(ParquetSqlRepository.latestCaptureOf(base(), "never-loaded")).isNull();
        assertThat(ParquetSqlRepository.capturesOf(base(), "never-loaded")).isEmpty();
    }

    /**
     * Two refs must never share a file name — that would re-merge their
     * captures and recreate the bug. A ref needing rewriting for the
     * filesystem carries a digest of the original so it stays distinct;
     * ParquetSqlSource uses a file path as its ref, so this is not theoretical.
     */
    @Test
    void sourceRefsThatSanitiseAlikeStillGetDistinctFiles() {
        String left = ParquetSink.fileToken("/var/data/one.parquet");
        String right = ParquetSink.fileToken("/var/data/two.parquet");

        assertThat(left).isNotEqualTo(right);
        assertThat(left).doesNotContain("/");
        // an already-safe ref is left legible
        assertThat(ParquetSink.fileToken("primary")).isEqualTo("primary-");
    }

    // ── one snapshot per source ──

    /**
     * THE INVARIANT, and it is not obvious now that a capture is plainly one
     * row per profile: two captures of ONE source must never both be read.
     *
     * <p>Performance Schema counters are cumulative since the last reset, so
     * consecutive captures of a source are overlapping snapshots of the same
     * counters rather than independent observations — adding them
     * double-counts every query in the overlap. Every read path takes exactly
     * one: PerformanceSchemaFileSourceFactory.scan takes max(createdOn) per
     * source ref, this takes the newest sealed capture, and a direct read is a
     * single snapshot. Summing ACROSS sources is the correct case, because
     * those are different servers with different counters (sqlx-4ww).
     *
     * <p>A reset is the one case where two captures of one source could validly
     * be summed, since the intervals are then disjoint. That is sqlx-65m, and
     * it must prove the reset before it sums.
     */
    @Test
    void aSourceResolvesToExactlyOneCaptureHoweverManyItHas() throws IOException {
        ParquetConfig config = new ParquetConfig();
        config.setFilename(base());
        ParquetSink sink = new ParquetSink(config);

        sink.write(capturedAt("2026-08-30T02:51:36Z"), List.of(profile()));
        sink.write(capturedAt("2026-08-31T02:51:36Z"), List.of(profile()));
        sink.write(capturedAt("2026-09-01T02:51:36Z"), List.of(profile()));

        assertThat(ParquetSqlRepository.capturesOf(base(), "primary"))
            .as("all three are kept — the archive needs them for gap detection")
            .hasSize(3);
        assertThat(ParquetSqlRepository.latestCaptureOf(base(), "primary").getFileName())
            .as("but only the newest is ever the corpus")
            .hasToString("sql-repository-primary-20260901T025136Z.parquet");
    }

    /** End to end: written where it says, and found again as the corpus. */
    @Test
    void aWrittenCaptureIsTheOneTheCorpusFinds() throws IOException {
        ParquetConfig config = new ParquetConfig();
        config.setFilename(base());
        ParquetSink sink = new ParquetSink(config);

        sink.write(capturedAt("2026-08-30T02:51:36Z"), List.of(profile()));

        Path found = ParquetSqlRepository.latestCapture(base());
        assertThat(found.toFile()).exists();
        assertThat(sink.readAll(found)).hasSize(1);
    }
}
