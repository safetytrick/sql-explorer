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

    @Test
    void twoCapturesGetTwoFiles() {
        Path first = ParquetSink.fileFor(base(), capturedAt("2026-08-30T02:51:36Z"));
        Path second = ParquetSink.fileFor(base(), capturedAt("2026-08-31T09:14:02Z"));

        assertThat(first).isNotEqualTo(second);
    }

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

    @Test
    void theCorpusIsTheNewestCapture() throws IOException {
        writeCapture("2026-08-30T02:51:36Z");
        writeCapture("2026-09-01T00:00:00Z");
        writeCapture("2026-08-15T12:00:00Z");

        assertThat(ParquetSqlRepository.latestCapture(base()).getFileName().toString())
            .isEqualTo("sql-repository-primary-20260901T000000Z.parquet");
    }

    @Test
    void fallsBackToTheLegacySingleFileWhenNoCaptureIsStamped() throws IOException {
        Files.createFile(directory.resolve("sql-repository.parquet"));

        assertThat(ParquetSqlRepository.latestCapture(base()).getFileName().toString())
            .isEqualTo("sql-repository.parquet");
    }

    @Test
    void aStampedCaptureIsPreferredOverTheLegacyFile() throws IOException {
        Files.createFile(directory.resolve("sql-repository.parquet"));
        writeCapture("2026-09-01T00:00:00Z");

        assertThat(ParquetSqlRepository.latestCapture(base()).getFileName().toString())
            .isEqualTo("sql-repository-primary-20260901T000000Z.parquet");
    }

    @Test
    void ignoresFilesThatAreNotCapturesOfThisBase() throws IOException {
        Files.createFile(directory.resolve("something-else-primary-20270101T000000Z.parquet"));
        Files.createFile(directory.resolve("sql-repository-primary-20260830T025136Z.parquet"));

        assertThat(ParquetSqlRepository.captures(base()))
            .extracting(path -> String.valueOf(path.getFileName()))
            .containsExactly("sql-repository-primary-20260830T025136Z.parquet");
    }

    @Test
    void anInProgressCaptureIsNotYetTheCorpus() throws IOException {
        writeCapture("2026-08-30T02:51:36Z");
        Files.createFile(directory.resolve("sql-repository-primary-20260901T000000Z.parquet"));

        assertThat(ParquetSqlRepository.latestCapture(base()).getFileName().toString())
            .isEqualTo("sql-repository-primary-20260830T025136Z.parquet");
    }

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

    @Test
    void aSourceDoesNotFindTheCapturesOfASourceWhoseNameExtendsIt() throws IOException {
        ParquetConfig config = new ParquetConfig();
        config.setFilename(base());
        ParquetSink sink = new ParquetSink(config);
        for (String[] capture : new String[][] {
            {"shard1", "2026-10-19T16:55:33Z"},
            {"shard1-replica-a", "2026-10-19T19:52:18Z"},
            {"shard1-etl", "2026-10-19T19:54:30Z"}}) {
            sink.write(new CaptureContext(CaptureContext.Origin.PERFORMANCE_SCHEMA, capture[0],
                Instant.parse(capture[1]), 1, null, null, Map.of()), List.of(profile()));
        }

        assertThat(ParquetSqlRepository.capturesOf(base(), "shard1")).singleElement()
            .satisfies(path -> assertThat(path.getFileName()).hasToString("sql-repository-shard1-20261019T165533Z.parquet"));
        assertThat(ParquetSqlRepository.latestCaptureOf(base(), "shard1").getFileName())
            .hasToString("sql-repository-shard1-20261019T165533Z.parquet");
        assertThat(ParquetSqlRepository.capturesOf(base(), "shard1-replica-a")).hasSize(1);
        assertThat(ParquetSqlRepository.capturesOf(base(), "shard1-etl")).hasSize(1);
    }

    @Test
    void anUncapturedSourceResolvesToNothing() throws IOException {
        ParquetConfig config = new ParquetConfig();
        config.setFilename(base());
        new ParquetSink(config).write(capturedAt("2026-08-30T02:51:36Z"), List.of(profile()));

        assertThat(ParquetSqlRepository.latestCaptureOf(base(), "never-loaded")).isNull();
        assertThat(ParquetSqlRepository.capturesOf(base(), "never-loaded")).isEmpty();
    }

    @Test
    void sourceRefsThatSanitiseAlikeStillGetDistinctFiles() {
        String left = ParquetSink.fileToken("/var/data/one.parquet");
        String right = ParquetSink.fileToken("/var/data/two.parquet");

        assertThat(left).isNotEqualTo(right);
        assertThat(left).doesNotContain("/");
        assertThat(ParquetSink.fileToken("primary")).isEqualTo("primary-");
    }

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
