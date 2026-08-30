package com.github.safetytrick.sqlexplorer.evidence.sink;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.LocalInputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provenance has to reach the <em>file</em>, not just the record that models
 * it. Parquet seals key/value metadata into the footer when the writer is
 * created, so this is also the test that the writer is still being built
 * lazily — construct it in the constructor again and the context arrives too
 * late, silently (sqlx-sy5).
 */
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

    /**
     * Where the capture actually landed. A context-carrying write is stamped
     * with its capture instant, so the configured path is not the file
     * (sqlx-sy5) — asking the repository is how anything else finds it too.
     */
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
        ParquetSink sink = sink(file);
        sink.capturing(new CaptureContext(
            CaptureContext.Origin.PARQUET_COPY, "primary",
            Instant.parse("2026-08-29T14:03:35Z"), 1,
            LocalDateTime.parse("2026-05-04T15:49:26"),
            LocalDateTime.parse("2026-08-29T14:03:35"),
            Map.of()));

        sink.write(profile("d1"));
        sink.close();

        assertThat(metadataOf(written(file)))
            .containsEntry("sqlexplorer.origin", "PARQUET_COPY")
            .containsEntry("sqlexplorer.originTable", "sys.x$statement_analysis")
            .containsEntry("sqlexplorer.sourceRef", "primary")
            .containsEntry("sqlexplorer.capturedAt", "2026-08-29T14:03:35Z")
            .containsEntry("sqlexplorer.observedFrom", "2026-05-04T15:49:26")
            .containsEntry("sqlexplorer.observedTo", "2026-08-29T14:03:35");
    }

    /** An unannounced file is readable and simply claims nothing. */
    @Test
    void aFileWrittenWithoutContextCarriesNoProvenance() throws IOException {
        Path file = directory.resolve("bare.parquet");
        ParquetSink sink = sink(file);

        sink.write(profile("d1"));
        sink.close();

        assertThat(metadataOf(file).keySet()).noneMatch(key -> key.startsWith("sqlexplorer."));
        assertThat(sink.readAll(file)).hasSize(1);
    }

    /**
     * The ordering constraint, asserted because breaking it is silent. Parquet
     * fixes the footer metadata at writer creation, so a context supplied after
     * the first row cannot reach the file — it must warn and be ignored rather
     * than appear to work.
     */
    @Test
    void contextArrivingAfterTheFirstRowDoesNotSilentlyAppearToWork() throws IOException {
        Path file = directory.resolve("late.parquet");
        ParquetSink sink = sink(file);

        sink.write(profile("d1"));
        sink.capturing(new CaptureContext(
            CaptureContext.Origin.PARQUET_COPY, "too-late",
            Instant.parse("2026-08-29T14:03:35Z"), 1, null, null, Map.of()));
        sink.close();

        assertThat(metadataOf(file).values()).doesNotContain("too-late");
    }

    /** Rows still round-trip; provenance must not cost the payload. */
    @Test
    void theProfilesAreStillReadable() throws IOException {
        Path file = directory.resolve("both.parquet");
        ParquetSink sink = sink(file);
        sink.capturing(new CaptureContext(
            CaptureContext.Origin.PARQUET_COPY, "primary",
            Instant.parse("2026-08-29T14:03:35Z"), 2, null, null, Map.of()));

        sink.write(profile("d1"));
        sink.write(profile("d2"));
        sink.close();

        assertThat(sink.readAll(written(file))).hasSize(2);
    }
}
