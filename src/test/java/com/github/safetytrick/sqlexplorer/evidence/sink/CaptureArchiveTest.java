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

    @Test
    void overlappingCapturesShowNoGap() {
        assertThat(report(
            capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00"),
            capture("primary", "2026-08-20T00:00:00Z", "2026-07-01T00:00:00", "2026-08-20T00:00:00"))
            .gaps()).isEmpty();
    }

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

    @Test
    void capturesAreOrderedByTheirRecordedInstantNotTheirListingOrder() {
        assertThat(report(
            capture("primary", "2026-08-20T00:00:00Z", "2026-07-01T00:00:00", "2026-08-20T00:00:00"),
            capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00"))
            .gaps()).isEmpty();
    }

    @Test
    void sourcesAreNeverComparedAgainstEachOther() {
        assertThat(report(
            capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00"),
            capture("replica", "2026-08-10T00:00:00Z", "2026-08-05T00:00:00", "2026-08-10T00:00:00"),
            capture("primary", "2026-08-20T00:00:00Z", "2026-07-01T00:00:00", "2026-08-20T00:00:00"))
            .gaps()).isEmpty();
    }

    @Test
    void aCaptureWithNoRecordedInstantIsSetAsideRatherThanGuessedAt() {
        CaptureArchive.ArchiveReport report = report(
            capture("primary", null, "2026-09-01T00:00:00", "2026-09-20T00:00:00"),
            capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00"));

        assertThat(report.gaps()).isEmpty();
        assertThat(report.unorderable()).containsExactly("capture-0.parquet");
    }

    @Test
    void aFileWithNoProvenanceAtAllIsUnorderable() {
        CaptureArchive.ArchiveReport report = CaptureArchive.report(
            List.of(new CaptureArchive.ArchivedCapture(Path.of("legacy.parquet"), true, null)), null);

        assertThat(report.gaps()).isEmpty();
        assertThat(report.unorderable()).containsExactly("legacy.parquet");
    }

    @Test
    void aCaptureWithNoObservedWindowSilencesItsPairRatherThanBeingSkippedOver() {
        assertThat(report(
            capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00"),
            capture("primary", "2026-08-10T00:00:00Z", null, null),
            capture("primary", "2026-08-20T00:00:00Z", "2026-08-15T00:00:00", "2026-08-20T00:00:00"))
            .gaps()).isEmpty();
    }

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
        new ParquetSink(config).write(context, List.of(profile("d1")));
    }

    @Test
    void theLegacySingleFileIsAccountedForRatherThanIgnored() throws IOException {
        Path configured = directory.resolve("profiles.parquet");
        writeCapture(configured, null);
        writeCapture(configured, capture("primary", "2026-08-01T00:00:00Z",
            "2026-07-01T00:00:00", "2026-08-01T00:00:00"));

        ParquetConfig config = new ParquetConfig();
        config.setFilename(configured.toString());

        assertThat(new CaptureArchive(config).scan().unorderable())
            .containsExactly("profiles.parquet");
    }

    @Test
    void theCaptureCurrentlyBeingWrittenIsNotCountedAsDoubt() throws IOException {
        Path configured = directory.resolve("profiles.parquet");
        writeCapture(configured, capture("primary", "2026-08-01T00:00:00Z",
            "2026-07-01T00:00:00", "2026-08-01T00:00:00"));
        writeCapture(configured, capture("primary", "2026-08-20T00:00:00Z",
            "2026-07-01T00:00:00", "2026-08-20T00:00:00"));
        java.nio.file.Files.createFile(directory.resolve("profiles-primary-20260901T000000Z.parquet"));

        ParquetConfig config = new ParquetConfig();
        config.setFilename(configured.toString());
        CaptureArchive.ArchiveReport report = new CaptureArchive(config).scan();

        assertThat(report.inProgress()).isEqualTo("profiles-primary-20260901T000000Z.parquet");
        assertThat(report.unorderable()).isEmpty();
        assertThat(report.gaps()).isEmpty();
    }

    @Test
    void anOlderFooterlessCaptureIsAFailedSampleNotDoubtAboutGaps() throws IOException {
        Path configured = directory.resolve("profiles.parquet");
        java.nio.file.Files.createFile(directory.resolve("profiles-primary-20260701T000000Z.parquet"));
        writeCapture(configured, capture("primary", "2026-08-01T00:00:00Z",
            "2026-07-01T00:00:00", "2026-08-01T00:00:00"));
        writeCapture(configured, capture("primary", "2026-08-20T00:00:00Z",
            "2026-07-01T00:00:00", "2026-08-20T00:00:00"));

        ParquetConfig config = new ParquetConfig();
        config.setFilename(configured.toString());
        CaptureArchive.ArchiveReport report = new CaptureArchive(config).scan();

        assertThat(report.inProgress()).isNull();
        assertThat(report.failed()).containsExactly("profiles-primary-20260701T000000Z.parquet");
        assertThat(report.unorderable()).isEmpty();
        assertThat(report.gaps()).isEmpty();
    }

    @Test
    void capturesWithAStableStartAreOneRun() {
        CaptureArchive.ArchiveReport report = report(
            capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00"),
            capture("primary", "2026-08-20T00:00:00Z", "2026-07-01T00:00:00", "2026-08-20T00:00:00"),
            capture("primary", "2026-08-30T00:00:00Z", "2026-07-01T00:00:00", "2026-08-30T00:00:00"));

        assertThat(report.runsOf("primary")).singleElement().satisfies(run -> {
            assertThat(run.captures()).isEqualTo(3);
            assertThat(run.observedTo()).isEqualTo(LocalDateTime.parse("2026-08-30T00:00:00"));
        });
    }

    @Test
    void aResetStartsANewRun() {
        CaptureArchive.ArchiveReport report = report(
            capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00"),
            capture("primary", "2026-08-20T00:00:00Z", "2026-08-10T00:00:00", "2026-08-20T00:00:00"));

        assertThat(report.runsOf("primary")).hasSize(2);
        assertThat(report.runsOf("primary").getFirst().observedFrom())
            .isEqualTo(LocalDateTime.parse("2026-07-01T00:00:00"));
        assertThat(report.runsOf("primary").getLast().observedFrom())
            .isEqualTo(LocalDateTime.parse("2026-08-10T00:00:00"));
    }

    @Test
    void aResetWithTrafficResumingAtOnceIsABoundaryWithNoGap() {
        CaptureArchive.ArchiveReport report = report(
            capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00"),
            capture("primary", "2026-08-20T00:00:00Z", "2026-07-15T00:00:00", "2026-08-20T00:00:00"));

        assertThat(report.runsOf("primary")).as("the counters restarted").hasSize(2);
        assertThat(report.gaps()).as("but nothing went unobserved").isEmpty();
    }

    @Test
    void aResetThatLostTimeReportsAGapToo() {
        CaptureArchive.ArchiveReport report = report(
            capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00"),
            capture("primary", "2026-08-20T00:00:00Z", "2026-08-10T00:00:00", "2026-08-20T00:00:00"));

        assertThat(report.runsOf("primary")).hasSize(2);
        assertThat(report.gaps()).hasSize(1);
    }

    @Test
    void aCaptureWithNoWindowDoesNotSplitARun() {
        CaptureArchive.ArchiveReport report = report(
            capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00"),
            capture("primary", "2026-08-10T00:00:00Z", null, null),
            capture("primary", "2026-08-20T00:00:00Z", "2026-07-01T00:00:00", "2026-08-20T00:00:00"));

        assertThat(report.runsOf("primary")).hasSize(1);
    }

    @Test
    void oneCaptureIsOneRun() {
        assertThat(report(capture("primary", "2026-08-01T00:00:00Z",
            "2026-07-01T00:00:00", "2026-08-01T00:00:00")).runsOf("primary")).hasSize(1);
    }

    @Test
    void runsAreCountedPerSource() {
        CaptureArchive.ArchiveReport report = report(
            capture("primary", "2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00"),
            capture("primary", "2026-08-20T00:00:00Z", "2026-08-10T00:00:00", "2026-08-20T00:00:00"),
            capture("replica", "2026-08-20T00:00:00Z", "2026-07-01T00:00:00", "2026-08-20T00:00:00"));

        assertThat(report.runsOf("primary")).hasSize(2);
        assertThat(report.runsOf("replica")).hasSize(1);
        assertThat(report.runsOf("never-seen")).isEmpty();
    }

    @Test
    void gapsAreFoundInFilesTheSinkActuallyWrote() throws IOException {
        Path configured = directory.resolve("profiles.parquet");
        writeCapture(configured, capture("primary", "2026-08-01T00:00:00Z",
            "2026-07-01T00:00:00", "2026-08-01T00:00:00"));
        writeCapture(configured, capture("primary", "2026-08-20T00:00:00Z",
            "2026-08-10T00:00:00", "2026-08-20T00:00:00"));

        assertThat(ParquetSqlRepository.captures(configured.toString())).hasSize(2);
        assertThat(ParquetSqlRepository.latestCapture(configured.toString()).getFileName())
            .hasToString("profiles-primary-20260820T000000Z.parquet");

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
