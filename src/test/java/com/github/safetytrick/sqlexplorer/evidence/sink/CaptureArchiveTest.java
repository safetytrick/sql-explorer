package com.github.safetytrick.sqlexplorer.evidence.sink;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A gap is an assertion that traffic happened and nothing saw it, so both
 * directions of error matter: missing a real reset hides an unobserved window,
 * and inventing one impeaches a corpus that is actually complete. Overlap —
 * the normal case, because Performance Schema accumulates — must read as no
 * gap (sqlx-sy5).
 */
class CaptureArchiveTest {

    @TempDir
    Path directory;

    private static CaptureContext capture(String sourceRef, String capturedAt, String from, String to) {
        return new CaptureContext(
            CaptureContext.Origin.PARQUET_COPY, sourceRef,
            capturedAt == null ? null : Instant.parse(capturedAt), 1,
            from == null ? null : LocalDateTime.parse(from),
            to == null ? null : LocalDateTime.parse(to),
            Map.of());
    }

    private static CaptureArchive.ArchiveReport report(CaptureContext... captures) {
        List<CaptureArchive.ArchivedCapture> files = new ArrayList<>();
        for (int i = 0; i < captures.length; i++) {
            files.add(new CaptureArchive.ArchivedCapture(
                Path.of("capture-" + i + ".parquet"), true, captures[i]));
        }
        return CaptureArchive.report(files, null);
    }

    /**
     * The normal case. Performance Schema was not reset between the two reads,
     * so the later capture still contains what the earlier one saw and the
     * windows overlap. Nothing to report.
     */
    @Test
    void overlappingCapturesShowNoGap() {
        assertThat(report(
            capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00"),
            capture("primary", "2026-08-20T00:00:00Z", "2026-07-01T00:00:00", "2026-08-20T00:00:00"))
            .gaps()).isEmpty();
    }

    /**
     * The digest table was reset between the reads: the later capture's first
     * observed statement is after the earlier capture's last, and the interval
     * between them is the window nothing recorded.
     */
    @Test
    void aResetBetweenCapturesReportsTheUnobservedInterval() {
        CaptureArchive.ArchiveReport report = report(
            capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00"),
            capture("primary", "2026-08-20T00:00:00Z", "2026-08-10T00:00:00", "2026-08-20T00:00:00"));

        assertThat(report.gaps()).singleElement().satisfies(gap -> {
            assertThat(gap.sourceRef()).isEqualTo("primary");
            assertThat(gap.unobservedFrom()).isEqualTo(LocalDateTime.parse("2026-08-01T00:00:00"));
            assertThat(gap.unobservedTo()).isEqualTo(LocalDateTime.parse("2026-08-10T00:00:00"));
            assertThat(gap.earlierCapturedAt()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
            assertThat(gap.laterCapturedAt()).isEqualTo(Instant.parse("2026-08-20T00:00:00Z"));
        });
    }

    /**
     * Order comes from the recorded instant, not from the order files were
     * listed or named. A capture written out of sequence — restored from a
     * backup, copied in by hand — must not read as a reset.
     */
    @Test
    void capturesAreOrderedByTheirRecordedInstantNotTheirListingOrder() {
        assertThat(report(
            capture("primary", "2026-08-20T00:00:00Z", "2026-07-01T00:00:00", "2026-08-20T00:00:00"),
            capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00"))
            .gaps()).isEmpty();
    }

    /**
     * Two sources are two sampling streams. Comparing across them is how you
     * invent a gap: each of these is internally continuous, and only
     * interleaving them makes one look reset.
     */
    @Test
    void sourcesAreNeverComparedAgainstEachOther() {
        assertThat(report(
            capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00"),
            capture("replica", "2026-08-10T00:00:00Z", "2026-08-05T00:00:00", "2026-08-10T00:00:00"),
            capture("primary", "2026-08-20T00:00:00Z", "2026-07-01T00:00:00", "2026-08-20T00:00:00"))
            .gaps()).isEmpty();
    }

    /**
     * The trap this reader exists to avoid. A capture with no recorded instant
     * cannot be placed in the sequence, and placing it by guess would either
     * invent a gap or hide one — so it is set aside and said out loud.
     */
    @Test
    void aCaptureWithNoRecordedInstantIsSetAsideRatherThanGuessedAt() {
        CaptureArchive.ArchiveReport report = report(
            capture("primary", null, "2026-09-01T00:00:00", "2026-09-20T00:00:00"),
            capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00"));

        assertThat(report.gaps()).isEmpty();
        assertThat(report.unorderable()).containsExactly("capture-0.parquet");
    }

    /** Likewise a file predating provenance entirely: unknown, not assumed. */
    @Test
    void aFileWithNoProvenanceAtAllIsUnorderable() {
        CaptureArchive.ArchiveReport report = CaptureArchive.report(
            List.of(new CaptureArchive.ArchivedCapture(Path.of("legacy.parquet"), true, null)), null);

        assertThat(report.gaps()).isEmpty();
        assertThat(report.unorderable()).containsExactly("legacy.parquet");
    }

    /**
     * An empty capture states no window. The pair it sits in proves nothing,
     * and reaching past it to compare its neighbours would find gaps by
     * assuming what the empty capture would have said.
     */
    @Test
    void aCaptureWithNoObservedWindowSilencesItsPairRatherThanBeingSkippedOver() {
        assertThat(report(
            capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00"),
            capture("primary", "2026-08-10T00:00:00Z", null, null),
            capture("primary", "2026-08-20T00:00:00Z", "2026-08-15T00:00:00", "2026-08-20T00:00:00"))
            .gaps()).isEmpty();
    }

    /** A single capture has nothing to compare against, and says nothing. */
    @Test
    void oneCaptureIsNotEvidenceOfAnything() {
        assertThat(report(capture("primary", "2026-08-01T00:00:00Z",
            "2026-07-01T00:00:00", "2026-08-01T00:00:00")).gaps()).isEmpty();
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

    private void writeCapture(Path configured, CaptureContext context) throws IOException {
        ParquetConfig config = new ParquetConfig();
        config.setFilename(configured.toString());
        ParquetSink sink = new ParquetSink(config);
        if (context != null) {
            sink.capturing(context);
        }
        sink.write(profile("d1"));
        sink.close();
    }

    /**
     * A deployment that ran the single-file layout has its whole pre-migration
     * history in the un-stamped file. It is not a capture by the naming rule,
     * so nothing lists it — but it is real, readable rows covering some
     * unknown stretch, and the archive must account for it rather than drop
     * the earliest window without saying so.
     */
    @Test
    void theLegacySingleFileIsAccountedForRatherThanIgnored() throws IOException {
        Path configured = directory.resolve("profiles.parquet");
        // written before provenance existed: sealed, readable, says nothing
        writeCapture(configured, null);
        writeCapture(configured, capture("primary", "2026-08-01T00:00:00Z",
            "2026-07-01T00:00:00", "2026-08-01T00:00:00"));

        ParquetConfig config = new ParquetConfig();
        config.setFilename(configured.toString());

        assertThat(new CaptureArchive(config).scan().unorderable())
            .containsExactly("profiles.parquet");
    }

    /**
     * A running sql-explorer always has one footerless file — its own capture,
     * still open, because Parquet writes the footer at close. Counting that as
     * doubt would leave the gap signal permanently uncertain during normal
     * operation, and a signal that always warns is one people stop reading.
     * Its rows are the loaded corpus, so it hides no window.
     */
    @Test
    void theCaptureCurrentlyBeingWrittenIsNotCountedAsDoubt() throws IOException {
        Path configured = directory.resolve("profiles.parquet");
        writeCapture(configured, capture("primary", "2026-08-01T00:00:00Z",
            "2026-07-01T00:00:00", "2026-08-01T00:00:00"));
        writeCapture(configured, capture("primary", "2026-08-20T00:00:00Z",
            "2026-07-01T00:00:00", "2026-08-20T00:00:00"));
        // what an import in flight leaves behind: newest name, no footer yet
        java.nio.file.Files.createFile(directory.resolve("profiles-20260901T000000Z.parquet"));

        ParquetConfig config = new ParquetConfig();
        config.setFilename(configured.toString());
        CaptureArchive.ArchiveReport report = new CaptureArchive(config).scan();

        assertThat(report.inProgress()).isEqualTo("profiles-20260901T000000Z.parquet");
        assertThat(report.unorderable()).isEmpty();
        assertThat(report.gaps()).isEmpty();
    }

    /**
     * An older footerless file is a capture that never finished — a shutdown
     * that did not close the writer. It is reported, because an operator
     * should know captures are failing, but it is not doubt about gaps: an
     * unreadable file states no window, so the sealed captures on either side
     * are still compared with each other and a reset between them still shows.
     * What was lost is that sample, not the answer.
     */
    @Test
    void anOlderFooterlessCaptureIsAFailedSampleNotDoubtAboutGaps() throws IOException {
        Path configured = directory.resolve("profiles.parquet");
        java.nio.file.Files.createFile(directory.resolve("profiles-20260701T000000Z.parquet"));
        writeCapture(configured, capture("primary", "2026-08-01T00:00:00Z",
            "2026-07-01T00:00:00", "2026-08-01T00:00:00"));
        writeCapture(configured, capture("primary", "2026-08-20T00:00:00Z",
            "2026-07-01T00:00:00", "2026-08-20T00:00:00"));

        ParquetConfig config = new ParquetConfig();
        config.setFilename(configured.toString());
        CaptureArchive.ArchiveReport report = new CaptureArchive(config).scan();

        assertThat(report.inProgress()).isNull();
        assertThat(report.failed()).containsExactly("profiles-20260701T000000Z.parquet");
        assertThat(report.unorderable()).isEmpty();
        // the two completed captures overlap, and that is still decidable
        assertThat(report.gaps()).isEmpty();
    }

    /**
     * End to end over real files: the footers the sink writes are the footers
     * this reads, and the archive keeps every capture while the repository
     * still resolves to only the newest.
     */
    @Test
    void gapsAreFoundInFilesTheSinkActuallyWrote() throws IOException {
        Path configured = directory.resolve("profiles.parquet");
        writeCapture(configured, capture("primary", "2026-08-01T00:00:00Z",
            "2026-07-01T00:00:00", "2026-08-01T00:00:00"));
        writeCapture(configured, capture("primary", "2026-08-20T00:00:00Z",
            "2026-08-10T00:00:00", "2026-08-20T00:00:00"));

        assertThat(ParquetSqlRepository.captures(configured.toString())).hasSize(2);
        assertThat(ParquetSqlRepository.latestCapture(configured.toString()).getFileName())
            .hasToString("profiles-20260820T000000Z.parquet");

        ParquetConfig config = new ParquetConfig();
        config.setFilename(configured.toString());
        CaptureArchive.ArchiveReport report = new CaptureArchive(config).scan();

        assertThat(report.unorderable()).isEmpty();
        assertThat(report.gaps()).singleElement().satisfies(gap -> {
            assertThat(gap.unobservedFrom()).isEqualTo(LocalDateTime.parse("2026-08-01T00:00:00"));
            assertThat(gap.unobservedTo()).isEqualTo(LocalDateTime.parse("2026-08-10T00:00:00"));
        });
    }
}
