package com.github.safetytrick.sqlexplorer.evidence.sink;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.LocalInputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ParquetProvenanceTest {

    @TempDir
    Path directory;

    private ParquetSink sink(Path file) {
        ParquetConfig config = new ParquetConfig();
        config.setFilename(file.toString());
        return new ParquetSink(config);
    }

    private static SqlProfile profile(String digest) {
        return SqlProfile.newBuilder()
            .setQuery("select 1").setType("SELECT")
            .setDigest(digest).setNormalizedDigest(digest)
            .setSchemaName("app")
            .setQueryActivityStatus(QueryActivityStatus.ACTIVE)
            .setRuntimeMetrics(null)
            .completeBuild("app");
    }

    private Path written(Path configured) {
        return ParquetSqlRepository.latestCapture(configured.toString());
    }

    private Map<String, String> metadataOf(Path file) throws IOException {
        try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(file))) {
            return reader.getFooter().getFileMetaData().getKeyValueMetaData();
        }
    }

    @Test
    void aWrittenFileSaysWhereItCameFrom() throws IOException {
        Path file = directory.resolve("profiles.parquet");
        sink(file).write(new CaptureContext(
            CaptureContext.Origin.PARQUET_COPY, "primary",
            Instant.parse("2026-08-29T14:03:35Z"), 1,
            LocalDateTime.parse("2026-05-04T15:49:26"),
            LocalDateTime.parse("2026-08-29T14:03:35"),
            Map.of()), List.of(profile("d1")));

        assertThat(metadataOf(written(file)))
            .containsEntry("sqlexplorer.origin", "PARQUET_COPY")
            .containsEntry("sqlexplorer.originTable", "sys.x$statement_analysis")
            .containsEntry("sqlexplorer.sourceRef", "primary")
            .containsEntry("sqlexplorer.capturedAt", "2026-08-29T14:03:35Z")
            .containsEntry("sqlexplorer.observedFrom", "2026-05-04T15:49:26")
            .containsEntry("sqlexplorer.observedTo", "2026-08-29T14:03:35");
    }

    @Test
    void aFileWrittenWithoutContextCarriesNoProvenance() throws IOException {
        Path file = directory.resolve("bare.parquet");
        ParquetSink sink = sink(file);

        sink.write(null, List.of(profile("d1")));

        assertThat(metadataOf(file).keySet()).noneMatch(key -> key.startsWith("sqlexplorer."));
        assertThat(sink.readAll(file)).hasSize(1);
    }

    @Test
    void twoSourcesDoNotShareAFile() throws IOException {
        Path configured = directory.resolve("profiles.parquet");
        ParquetSink sink = sink(configured);

        Path first = sink.write(new CaptureContext(CaptureContext.Origin.PERFORMANCE_SCHEMA, "primary",
            Instant.parse("2026-08-29T14:03:35Z"), 2, null, null, Map.of()),
            List.of(profile("a1"), profile("a2")));
        Path second = sink.write(new CaptureContext(CaptureContext.Origin.PERFORMANCE_SCHEMA, "explorer",
            Instant.parse("2026-08-29T14:04:00Z"), 1, null, null, Map.of()),
            List.of(profile("b1")));

        assertThat(first).isNotEqualTo(second);
        assertThat(sink.readAll(first)).hasSize(2);
        assertThat(sink.readAll(second)).hasSize(1);
        assertThat(metadataOf(first)).containsEntry("sqlexplorer.sourceRef", "primary");
        assertThat(metadataOf(second)).containsEntry("sqlexplorer.sourceRef", "explorer");
    }

    @Test
    void aCaptureIsSealedWhenTheWriteReturns() throws IOException {
        Path configured = directory.resolve("profiles.parquet");
        ParquetSink sink = sink(configured);

        Path written = sink.write(new CaptureContext(CaptureContext.Origin.PERFORMANCE_SCHEMA, "primary",
            Instant.parse("2026-08-29T14:03:35Z"), 1, null, null, Map.of()), List.of(profile("d1")));

        assertThat(sink.readAll(written)).hasSize(1);
        assertThat(written.toFile().length()).isGreaterThan(4);
    }

    @Test
    void theProfilesAreStillReadable() throws IOException {
        Path file = directory.resolve("both.parquet");
        ParquetSink sink = sink(file);

        sink.write(new CaptureContext(
            CaptureContext.Origin.PARQUET_COPY, "primary",
            Instant.parse("2026-08-29T14:03:35Z"), 2, null, null, Map.of()),
            List.of(profile("d1"), profile("d2")));

        assertThat(sink.readAll(written(file))).hasSize(2);
    }
}
