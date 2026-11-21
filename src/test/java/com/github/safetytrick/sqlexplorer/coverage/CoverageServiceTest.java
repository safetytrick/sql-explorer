package com.github.safetytrick.sqlexplorer.coverage;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import com.github.safetytrick.sqlexplorer.evidence.SqlImportStats;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepository;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryManager;
import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureArchive;
import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetConfig;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetSink;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlInput;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoverageServiceTest {

    @TempDir
    Path directory;

    private static final Map<String, String> HEALTHY_SERVER = Map.of(
        "version", "8.4.8",
        "performance_schema_digests_size", "10000",
        "performance_schema_max_digest_length", "4096",
        "max_digest_length", "4096",
        "Performance_schema_digest_lost", "0",
        "Uptime", "10205030");

    private List<SqlRepositoryManager.SourceFailure> failures = List.of();

    private CoverageService service(SqlRepository... repositories) {
        SqlRepositoryManager manager = mock(SqlRepositoryManager.class);
        when(manager.getFailedSources("env")).thenReturn(failures);
        when(manager.getLoadState("env")).thenReturn(
            new SqlRepositoryManager.LoadState(repositories.length, Instant.parse("2026-08-30T18:33:00Z"), null, true));
        when(manager.getRepositories("env")).thenReturn(List.of(repositories));
        ParquetConfig config = new ParquetConfig();
        config.setFilename(directory.resolve("none.parquet").toString());
        return new CoverageService(manager, new CaptureArchive(config), mock(EnvironmentRegistry.class));
    }

    private static SqlRepository repository(CaptureContext capture, SqlImportStats stats) {
        return new SqlRepository() {
            @Override public SqlProfile getByDigest(String id) { return null; }
            @Override public Stream<SqlProfile> getTableQueries(TableReference table) { return Stream.empty(); }
            @Override public Stream<SqlProfile> getQueriesForColumn(TableReference t, String c) { return Stream.empty(); }
            @Override public Stream<TableReference> getAllTableReferences() { return Stream.empty(); }
            @Override public Stream<SqlProfile> getAllAnalyzedSql() { return Stream.empty(); }
            @Override public SqlSource getSource() {
                return new SqlSource() {
                    @Override public Stream<SqlInput> getSql() { return Stream.empty(); }
                    @Override public Instant getCreatedOn() { return Instant.parse("2026-08-30T18:33:00Z"); }
                    @Override public String getSourceRef() { return "primary"; }
                };
            }
            @Override public SqlImportStats getImportStats() { return stats; }
            @Override public CaptureContext getCaptureContext() { return capture; }
        };
    }

    private static CaptureContext capture(Map<String, String> server) {
        return new CaptureContext(CaptureContext.Origin.PARQUET_COPY, "primary",
            Instant.parse("2026-08-30T18:33:14Z"), 4243,
            LocalDateTime.parse("2026-05-04T15:49:26"),
            LocalDateTime.parse("2026-08-30T18:33:15"),
            server);
    }

    private static SqlImportStats stats(int count, int truncated) {
        return new SqlImportStats(count, 0, 0, 0, 0, truncated, 0, 0, 0);
    }

    private static Coverage.Finding finding(Coverage.Report report, Coverage.Signal signal) {
        return report.sources().get(0).findings().stream()
            .filter(f -> f.signal() == signal).findFirst().orElseThrow();
    }

    @Test
    void digestsLostIsPreferredOverTheRowCountInference() {
        Map<String, String> server = new java.util.HashMap<>(HEALTHY_SERVER);
        server.put("Performance_schema_digest_lost", "37");
        Coverage.Report report = service(repository(capture(server), stats(4243, 0))).report("env");

        Coverage.Finding capacity = finding(report, Coverage.Signal.DIGEST_CAPACITY);
        assertThat(capacity.verdict()).isEqualTo(Coverage.Verdict.IMPAIRED);
        assertThat(capacity.measurement()).isEqualTo("37 digests lost");
    }

    @Test
    void anUnfilledDigestTableSaysSoRatherThanStayingSilent() {
        Coverage.Finding capacity = finding(
            service(repository(capture(HEALTHY_SERVER), stats(4243, 0))).report("env"),
            Coverage.Signal.DIGEST_CAPACITY);

        assertThat(capacity.verdict()).isEqualTo(Coverage.Verdict.OK);
        assertThat(capacity.explanation()).contains("never ran out of room");
    }

    @Test
    void aFullDigestTableIsImpaired() {
        Map<String, String> server = new java.util.HashMap<>(HEALTHY_SERVER);
        server.remove("Performance_schema_digest_lost");
        Coverage.Finding capacity = finding(
            service(repository(capture(server), stats(10000, 0))).report("env"),
            Coverage.Signal.DIGEST_CAPACITY);

        assertThat(capacity.verdict()).isEqualTo(Coverage.Verdict.IMPAIRED);
        assertThat(capacity.measurement()).isEqualTo("10000 of 10000 digests");
    }

    @Test
    void aSourceWithNoRecordedServerSettingsIsUnknownNotFine() {
        Coverage.Finding capacity = finding(
            service(repository(capture(Map.of()), stats(4243, 0))).report("env"),
            Coverage.Signal.DIGEST_CAPACITY);

        assertThat(capacity.verdict()).isEqualTo(Coverage.Verdict.UNKNOWN);
        assertThat(capacity.basis()).contains("no capture-time server settings");
        assertThat(capacity.explanation()).contains("not safe to assume");
    }

    @Test
    void truncationIsReportedWithTheServersOwnLimit() {
        Coverage.Finding truncation = finding(
            service(repository(capture(HEALTHY_SERVER), stats(4243, 9))).report("env"),
            Coverage.Signal.DIGEST_TRUNCATION);

        assertThat(truncation.verdict()).isEqualTo(Coverage.Verdict.IMPAIRED);
        assertThat(truncation.measurement()).isEqualTo("9 truncated");
        assertThat(truncation.basis()).contains("effective digest limit of 4096");
    }

    @Test
    void aLowDigestLimitIsWorthMentioningEvenWithNothingTruncated() {
        Map<String, String> server = new java.util.HashMap<>(HEALTHY_SERVER);
        server.put("performance_schema_max_digest_length", "1024");
        server.put("max_digest_length", "1024");
        Coverage.Finding truncation = finding(
            service(repository(capture(server), stats(4243, 0))).report("env"),
            Coverage.Signal.DIGEST_TRUNCATION);

        assertThat(truncation.verdict()).isEqualTo(Coverage.Verdict.OK);
        assertThat(truncation.explanation()).contains("4096");
    }

    /**
     * The lower of the two variables binds, and reporting the higher one would
     * tell an operator the limit is fine when it is not. Measured on a stock
     * MySQL 8.4.4: with max_digest_length at 1024 and its performance_schema
     * companion at 4096, a 3.6KB statement is still cut at 1024. Raising only
     * the performance_schema one — which is the advice usually given — changes
     * nothing, because a digest cannot be stored longer than it was computed.
     */
    @Test
    void theLowerOfTheTwoDigestLimitsIsTheOneThatBinds() {
        Map<String, String> server = new java.util.HashMap<>(HEALTHY_SERVER);
        server.put("performance_schema_max_digest_length", "4096");
        server.put("max_digest_length", "1024");
        Coverage.Finding truncation = finding(
            service(repository(capture(server), stats(4243, 9))).report("env"),
            Coverage.Signal.DIGEST_TRUNCATION);

        assertThat(truncation.basis()).contains("effective digest limit of 1024");
        assertThat(truncation.explanation()).contains("BOTH");
    }

    @Test
    void aServerThatRecordedOnlyOneLimitIsJudgedOnThatOne() {
        Map<String, String> server = new java.util.HashMap<>(HEALTHY_SERVER);
        server.remove("max_digest_length");
        server.put("performance_schema_max_digest_length", "1024");
        Coverage.Finding truncation = finding(
            service(repository(capture(server), stats(4243, 9))).report("env"),
            Coverage.Signal.DIGEST_TRUNCATION);

        assertThat(truncation.verdict()).isEqualTo(Coverage.Verdict.IMPAIRED);
        assertThat(truncation.basis()).contains("effective digest limit of 1024");
        assertThat(truncation.basis()).contains("max_digest_length not recorded");
    }

    @Test
    void truncationWithNoRecordedLimitDoesNotImplyTheDefault() {
        Coverage.Finding truncation = finding(
            service(repository(capture(Map.of()), stats(4243, 9))).report("env"),
            Coverage.Signal.DIGEST_TRUNCATION);

        assertThat(truncation.basis()).contains("limits were not recorded");
        assertThat(truncation.explanation()).doesNotContain("1024");
    }

    @Test
    void theWindowIsStatedInDaysSoAnAbsenceCanBeQuantified() {
        Coverage.Report report = service(repository(capture(HEALTHY_SERVER), stats(4243, 0))).report("env");

        assertThat(report.sources().get(0).window().days()).isEqualTo(118);
        assertThat(finding(report, Coverage.Signal.OBSERVATION_WINDOW).verdict()).isEqualTo(Coverage.Verdict.OK);
    }

    @Test
    void aWindowLongerThanServerUptimeIsContradictoryAndSaysSo() {
        Map<String, String> server = new java.util.HashMap<>(HEALTHY_SERVER);
        server.put("Uptime", "3600");
        Coverage.Finding window = finding(
            service(repository(capture(server), stats(4243, 0))).report("env"),
            Coverage.Signal.OBSERVATION_WINDOW);

        assertThat(window.verdict()).isEqualTo(Coverage.Verdict.IMPAIRED);
        assertThat(window.explanation()).contains("disagree");
    }

    @Test
    void noRecordedWindowIsUnknown() {
        CaptureContext noWindow = new CaptureContext(CaptureContext.Origin.CSV_EXPORT, "primary",
            Instant.parse("2026-08-30T18:33:14Z"), 10, null, null, Map.of());
        Coverage.Report report = service(repository(noWindow, stats(10, 0))).report("env");

        assertThat(report.sources().get(0).window()).isNull();
        assertThat(finding(report, Coverage.Signal.OBSERVATION_WINDOW).verdict())
            .isEqualTo(Coverage.Verdict.UNKNOWN);
    }

    @Test
    void aSourceWithNoProvenanceStillNamesTheOriginTable() {
        Coverage.Finding provenance = finding(
            service(repository(null, stats(10, 0))).report("env"),
            Coverage.Signal.PROVENANCE);

        assertThat(provenance.verdict()).isEqualTo(Coverage.Verdict.UNKNOWN);
        assertThat(provenance.explanation()).contains(CaptureContext.ORIGIN_TABLE);
    }

    @Test
    void hopsFromTheOriginTableAreStated() {
        CaptureContext direct = new CaptureContext(CaptureContext.Origin.PERFORMANCE_SCHEMA, "primary",
            Instant.parse("2026-08-30T18:33:14Z"), 10, null, null, HEALTHY_SERVER);
        assertThat(finding(service(repository(direct, stats(10, 0))).report("env"),
            Coverage.Signal.PROVENANCE).explanation()).contains("straight off the source server");

        assertThat(finding(service(repository(capture(HEALTHY_SERVER), stats(10, 0))).report("env"),
            Coverage.Signal.PROVENANCE).explanation()).contains("two hops");
    }

    @Test
    void aSourceWithNoArchiveCannotSayWhatItIsMissing() {
        Coverage.Finding history = finding(
            service(repository(capture(HEALTHY_SERVER), stats(4243, 0))).report("env"),
            Coverage.Signal.ARCHIVED_HISTORY);

        assertThat(history.verdict()).isEqualTo(Coverage.Verdict.UNKNOWN);
        assertThat(history.explanation()).contains("never been written down");
    }

    @Test
    void oneUnbrokenRunIsReportedAsSuch() throws IOException {
        Coverage.Finding history = archiveWith(List.of(
            written("2026-08-01T00:00:00Z", "2026-07-01T00:00:00", "2026-08-01T00:00:00"),
            written("2026-08-20T00:00:00Z", "2026-07-01T00:00:00", "2026-08-20T00:00:00")));

        assertThat(history.verdict()).isEqualTo(Coverage.Verdict.OK);
        assertThat(history.measurement()).isEqualTo("one unbroken period");
        assertThat(history.explanation()).contains("all the evidence there is");
    }

    @Test
    void aResetMeansTheArchiveHoldsMoreThanTheCorpus() throws IOException {
        Coverage.Finding history = archiveWith(List.of(
            written("2026-06-01T00:00:00Z", "2026-01-01T00:00:00", "2026-06-01T00:00:00"),
            written("2026-08-20T00:00:00Z", "2026-08-10T00:00:00", "2026-08-20T00:00:00")));

        assertThat(history.verdict()).isEqualTo(Coverage.Verdict.IMPAIRED);
        assertThat(history.measurement()).isEqualTo("1 reset");
        assertThat(history.basis()).contains("archive spans");
        assertThat(history.explanation()).contains("disjoint");
        assertThat(history.explanation()).contains("double-count");
    }

    private CaptureContext written(String capturedAt, String from, String to) {
        return new CaptureContext(CaptureContext.Origin.PERFORMANCE_SCHEMA, "primary",
            Instant.parse(capturedAt), 1,
            LocalDateTime.parse(from), LocalDateTime.parse(to), HEALTHY_SERVER);
    }

    private Coverage.Finding archiveWith(List<CaptureContext> captures) throws IOException {
        ParquetConfig config = new ParquetConfig();
        config.setFilename(directory.resolve("sql-repository.parquet").toString());
        ParquetSink sink = new ParquetSink(config);
        for (CaptureContext context : captures) {
            sink.write(context, List.of(SqlProfile.newBuilder()
                .setQuery("select 1").setType("SELECT")
                .setDigest("d").setNormalizedDigest("d").setSchemaName("app")
                .setQueryActivityStatus(com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus.ACTIVE)
                .setRuntimeMetrics(null).completeBuild("app")));
        }
        SqlRepositoryManager manager = mock(SqlRepositoryManager.class);
        when(manager.getLoadState("env")).thenReturn(
            new SqlRepositoryManager.LoadState(1, Instant.parse("2026-08-30T18:33:00Z"), null, true));
        when(manager.getRepositories("env")).thenReturn(
            List.of(repository(capture(HEALTHY_SERVER), stats(1, 0))));

        return new CoverageService(manager, new CaptureArchive(config), mock(EnvironmentRegistry.class)).report("env")
            .sources().getFirst().findings().stream()
            .filter(f -> f.signal() == Coverage.Signal.ARCHIVED_HISTORY).findFirst().orElseThrow();
    }

    @Test
    void anUnloadedEnvironmentReportsNoSources() {
        SqlRepositoryManager manager = mock(SqlRepositoryManager.class);
        when(manager.getLoadState("env")).thenReturn(null);
        when(manager.getRepositories("env")).thenReturn(List.of());
        ParquetConfig config = new ParquetConfig();
        config.setFilename(directory.resolve("none.parquet").toString());

        Coverage.Report report = new CoverageService(manager, new CaptureArchive(config), mock(EnvironmentRegistry.class)).report("env");

        assertThat(report.loaded()).isFalse();
        assertThat(report.sources()).isEmpty();
        assertThat(report.originTable()).isEqualTo(CaptureContext.ORIGIN_TABLE);
    }

    @Test
    void sourcesAreReportedSeparatelyRatherThanCombined() {
        Map<String, String> impaired = new java.util.HashMap<>(HEALTHY_SERVER);
        impaired.put("Performance_schema_digest_lost", "500");
        Coverage.Report report = service(
            repository(capture(HEALTHY_SERVER), stats(4243, 0)),
            repository(capture(impaired), stats(4243, 0))).report("env");

        assertThat(report.sources()).hasSize(2);
        assertThat(report.sources().get(0).findings().stream()
            .filter(f -> f.signal() == Coverage.Signal.DIGEST_CAPACITY).findFirst().orElseThrow().verdict())
            .isEqualTo(Coverage.Verdict.OK);
        assertThat(report.sources().get(1).findings().stream()
            .filter(f -> f.signal() == Coverage.Signal.DIGEST_CAPACITY).findFirst().orElseThrow().verdict())
            .isEqualTo(Coverage.Verdict.IMPAIRED);
    }

    @Test
    void everySignalIsAnsweredEvenWhenNothingWasRecorded() {
        Coverage.Report report = service(repository(null, stats(10, 0))).report("env");

        assertThat(report.sources().get(0).findings())
            .extracting(Coverage.Finding::signal)
            .containsExactlyInAnyOrder(java.util.Arrays.stream(Coverage.Signal.values())
                .filter(signal -> signal != Coverage.Signal.UNREACHABLE)
                .toArray(Coverage.Signal[]::new));
    }

    @Test
    void everyFindingCarriesItsOwnExplanation() {
        Coverage.Report report = service(repository(capture(HEALTHY_SERVER), stats(4243, 9))).report("env");

        assertThat(report.sources().get(0).findings()).allSatisfy(f -> {
            assertThat(f.explanation()).isNotBlank();
            assertThat(f.measurement()).isNotBlank();
            assertThat(f.basis()).isNotBlank();
        });
    }

    @Test
    void aSourceTheLoadCouldNotReadIsListedAsImpaired() {
        failures = List.of(new SqlRepositoryManager.SourceFailure("sharing",
            "Access denied for user 'reader'@'10.0.0.1' (using password: YES)"));

        Coverage.Report report = service(repository(capture(HEALTHY_SERVER), stats(4243, 0))).report("env");

        assertThat(report.sources()).extracting(Coverage.SourceCoverage::sourceRef)
            .containsExactly("primary", "sharing");
        Coverage.SourceCoverage sharing = report.sources().get(1);
        assertThat(sharing.profiles()).isZero();
        assertThat(sharing.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.signal()).isEqualTo(Coverage.Signal.UNREACHABLE);
            assertThat(finding.verdict()).isEqualTo(Coverage.Verdict.IMPAIRED);
            assertThat(finding.explanation()).contains("Access denied for user 'reader'");
        });
    }

    private Coverage.Finding pendingFinding(int reused, int pending) {
        SqlImportStats stats = new SqlImportStats(10, 0, 0, 0, 0, 0, 0, reused, pending);
        return service(repository(capture(HEALTHY_SERVER), stats)).report("env").sources().getFirst().findings()
            .stream().filter(f -> f.signal() == Coverage.Signal.PENDING_ANALYSIS).findFirst().orElseThrow();
    }

    @Test
    void statementsWaitingOnAnUnconfirmedSchemaMakeTheSourceIncomplete() {
        Coverage.Finding finding = pendingFinding(4000, 12);

        assertThat(finding.verdict()).isEqualTo(Coverage.Verdict.IMPAIRED);
        assertThat(finding.measurement()).isEqualTo("12 waiting, 4000 reused");
        assertThat(finding.explanation()).contains("12 statement(s)");
    }

    @Test
    void nothingWaitingIsFine() {
        assertThat(pendingFinding(4000, 0).verdict()).isEqualTo(Coverage.Verdict.OK);
    }
}
