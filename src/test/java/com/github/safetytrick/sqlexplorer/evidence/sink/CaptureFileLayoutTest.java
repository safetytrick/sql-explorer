package com.github.safetytrick.sqlexplorer.evidence.sink;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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

    @Test
    void aCaptureIsWrittenBesideTheConfiguredNameRatherThanOverIt() {
        Path file = ParquetSink.fileFor(base(), capturedAt("2026-08-30T02:51:36Z"));

        assertThat(String.valueOf(file.getFileName())).isEqualTo("sql-repository-20260830T025136Z.parquet");
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
        Files.createFile(directory.resolve("sql-repository-20260830T025136Z.parquet"));
        Files.createFile(directory.resolve("sql-repository-20260901T000000Z.parquet"));
        Files.createFile(directory.resolve("sql-repository-20260815T120000Z.parquet"));

        assertThat(ParquetSqlRepository.latestCapture(base()).getFileName().toString())
            .isEqualTo("sql-repository-20260901T000000Z.parquet");
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
        Files.createFile(directory.resolve("sql-repository-20260901T000000Z.parquet"));

        assertThat(ParquetSqlRepository.latestCapture(base()).getFileName().toString())
            .isEqualTo("sql-repository-20260901T000000Z.parquet");
    }

    /** Unrelated files in the directory must not be mistaken for captures. */
    @Test
    void ignoresFilesThatAreNotCapturesOfThisBase() throws IOException {
        Files.createFile(directory.resolve("something-else-20270101T000000Z.parquet"));
        Files.createFile(directory.resolve("sql-repository-20260830T025136Z.parquet"));

        assertThat(ParquetSqlRepository.latestCapture(base()).getFileName().toString())
            .isEqualTo("sql-repository-20260830T025136Z.parquet");
    }

    /** End to end: written where it says, and found again as the corpus. */
    @Test
    void aWrittenCaptureIsTheOneTheCorpusFinds() throws IOException {
        ParquetConfig config = new ParquetConfig();
        config.setFilename(base());
        ParquetSink sink = new ParquetSink(config);
        sink.capturing(capturedAt("2026-08-30T02:51:36Z"));

        sink.write(profile());
        sink.close();

        Path found = ParquetSqlRepository.latestCapture(base());
        assertThat(found.toFile()).exists();
        assertThat(sink.readAll(found)).hasSize(1);
    }
}
