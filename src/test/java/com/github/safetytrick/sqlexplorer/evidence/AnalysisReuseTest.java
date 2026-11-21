package com.github.safetytrick.sqlexplorer.evidence;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.analysis.AnalyzerVersion;
import com.github.safetytrick.sqlexplorer.analysis.IgnoredSchemas;
import com.github.safetytrick.sqlexplorer.analysis.SqlAnalysisService;
import com.github.safetytrick.sqlexplorer.analysis.parser.NormalizedQueryDigest;
import com.github.safetytrick.sqlexplorer.analysis.parser.QueryNormalizer;
import com.github.safetytrick.sqlexplorer.analysis.parser.SqlParser;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.evidence.SqlEvidenceInputService.AnalysisContext;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetConfig;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetSink;
import com.github.safetytrick.sqlexplorer.evidence.source.MeteredSql;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlInput;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AnalysisReuseTest {

    @TempDir
    Path directory;

    private static final String DIGEST = "4d1c7a0f9e3b2c5d8a6f1e0b7c9d2a4e6f8b0c1d3e5a7f9b2c4d6e8f0a1b3c5d";
    private static final String QUERY = "SELECT `id` FROM `users` WHERE `id` = ?";

    private SqlAnalysisService analysis;
    private SqlEvidenceInputService service;
    private final SqlSource source = new SqlSource() {
        @Override public Stream<SqlInput> getSql() { return Stream.empty(); }
        @Override public Instant getCreatedOn() { return Instant.parse("2026-10-19T17:00:00Z"); }
        @Override public String getSourceRef() { return "shard1"; }
    };

    private ParquetConfig config;
    private ParquetSink sink;

    @BeforeEach
    void setUp() {
        config = new ParquetConfig();
        config.setFilename(directory.resolve("capture.parquet").toString());
        analysis = spy(new SqlAnalysisService());
        sink = new ParquetSink(config);
        service = new SqlEvidenceInputService(false, sink, analysis,
            new NormalizedQueryDigest(new QueryNormalizer(), new SqlParser()), new SimpleMeterRegistry(),
            new IgnoredSchemas());
    }

    private static MeteredSql row(String db, long executions) {
        MeteredSql row = new MeteredSql();
        row.setQuery(QUERY);
        row.setSchemaName(db);
        row.setDigest(DIGEST);
        row.setExecuted(executions);
        row.setTotalTime(BigDecimal.ONE);
        row.setMaxTime(BigDecimal.ONE);
        row.setAverageTime(BigDecimal.ONE);
        row.setLockTime(BigDecimal.ZERO);
        row.setFirstSeen(LocalDateTime.parse("2026-10-01T00:00:00"));
        row.setLastSeen(LocalDateTime.parse("2026-10-19T16:00:00"));
        return row;
    }

    private SqlRepository load(AnalysisContext context, MeteredSql... rows) {
        List<SqlInput> inputs = new java.util.ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            inputs.add(new SqlInput(i, rows[i], source));
        }
        return service.fromPerformanceSchema(source, inputs.stream(), null, context);
    }

    private static SqlProfile only(SqlRepository repository) {
        List<SqlProfile> profiles = repository.getAllAnalyzedSql().toList();
        assertThat(profiles).hasSize(1);
        return profiles.getFirst();
    }

    private SqlProfile priorAnalysis(String version, String sessionSchema) {
        SqlProfile analysed = only(load(AnalysisContext.ANALYSE_EVERYTHING, row(sessionSchema, 10)));
        return SqlProfile.newBuilder(analysed)
            .setTables(List.of(TableReference.newTableReference("app", "users")))
            .setAnalyzerVersion(version)
            .build();
    }

    @Test
    void aFreshAnalysisRecordsItsVersionAndTheRowsOwnSchema() throws Exception {
        SqlProfile profile = only(load(AnalysisContext.ANALYSE_EVERYTHING, row("app", 10)));

        assertThat(profile.getAnalyzerVersion()).isEqualTo(AnalyzerVersion.CURRENT);
        assertThat(profile.getSessionSchema()).isEqualTo("app");
        assertThat(profile.getSources()).as("where the row was seen (sqlx-a9h.1)").containsExactly("shard1");
    }

    @Test
    void aStatementAnalysedBeforeIsReusedWithoutParsing() throws Exception {
        SqlProfile prior = priorAnalysis(AnalyzerVersion.CURRENT, "app");
        org.mockito.Mockito.clearInvocations(analysis);

        SqlRepository repository = load(new AnalysisContext(AnalysisReuse.of(List.of(prior)), true), row("app", 99));

        verify(analysis, never()).analyzeSql(any(), any(), any());
        SqlProfile reused = only(repository);
        assertThat(reused.getTables()).as("the earlier analysis").isEqualTo(prior.getTables());
        assertThat(reused.getRuntimeMetrics().getExecuted()).as("this row's own metrics").isEqualTo(99);
        assertThat(reused.getSources()).as("this row's own source, not copied").containsExactly("shard1");
        assertThat(repository.getImportStats().reused()).isEqualTo(1);
    }

    @Test
    void reuseDoesNotWaitForACurrentSchema() throws Exception {
        SqlProfile prior = priorAnalysis(AnalyzerVersion.CURRENT, "app");

        SqlRepository repository = load(new AnalysisContext(AnalysisReuse.of(List.of(prior)), false), row("app", 99));

        assertThat(only(repository).getTables()).isEqualTo(prior.getTables());
        assertThat(repository.getImportStats().pending()).isZero();
    }

    @Test
    void anAnalysisFromAnOlderAnalyzerIsNotReused() throws Exception {
        SqlProfile prior = priorAnalysis("a0.n" + QueryNormalizer.VERSION, "app");
        org.mockito.Mockito.clearInvocations(analysis);

        SqlRepository repository = load(new AnalysisContext(AnalysisReuse.of(List.of(prior)), true), row("app", 99));

        verify(analysis, times(1)).analyzeSql(any(), any(), any());
        assertThat(only(repository).getAnalyzerVersion()).isEqualTo(AnalyzerVersion.CURRENT);
        assertThat(repository.getImportStats().reused()).isZero();
    }

    @Test
    void theSameDigestUnderAnotherDefaultSchemaIsNotReused() throws Exception {
        SqlProfile prior = priorAnalysis(AnalyzerVersion.CURRENT, "app");
        org.mockito.Mockito.clearInvocations(analysis);

        SqlRepository repository = load(new AnalysisContext(AnalysisReuse.of(List.of(prior)), true), row("audit", 99));

        verify(analysis, times(1)).analyzeSql(any(), any(), any());
        assertThat(repository.getImportStats().reused()).isZero();
    }

    @Test
    void aNewStatementWaitsWhenTheSchemaIsNotCurrent() throws Exception {
        SqlRepository repository = load(new AnalysisContext(AnalysisReuse.NONE, false), row("app", 99));

        verify(analysis, never()).analyzeSql(any(), any(), any());
        assertThat(repository.getAllAnalyzedSql()).isEmpty();
        assertThat(repository.getImportStats().pending()).isEqualTo(1);
    }

    @Test
    void theIndexKeepsTheMostRecentlySeenAnalysisPerKey() throws Exception {
        SqlProfile older = SqlProfile.newBuilder(priorAnalysis(AnalyzerVersion.CURRENT, "app"))
            .setType("OLDER").build();
        SqlProfile newer = SqlProfile.newBuilder(older).setType("NEWER")
            .setRuntimeMetrics(com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics.newBuilder(older.getRuntimeMetrics())
                .setLastSeen(LocalDateTime.parse("2026-10-20T00:00:00")).build())
            .build();

        assertThat(AnalysisReuse.of(List.of(newer, older)).find(DIGEST, "app").getType()).isEqualTo("NEWER");
        assertThat(AnalysisReuse.of(List.of(older, newer)).find(DIGEST, "app").getType()).isEqualTo("NEWER");
    }

    @Test
    void theNextLoadReusesWhatTheLastLoadsCaptureRecorded() throws Exception {
        load(AnalysisContext.ANALYSE_EVERYTHING, row("app", 10));    // writes shard1's capture
        org.mockito.Mockito.clearInvocations(analysis);

        AnalysisReuse fromCapture = new CaptureRehydrator(config, sink).reuseFor(List.of("shard1", "never-captured"));
        SqlRepository repository = load(new AnalysisContext(fromCapture, true), row("app", 99));

        assertThat(fromCapture.find(DIGEST, "app")).isNotNull();
        verify(analysis, never()).analyzeSql(any(), any(), any());
        assertThat(repository.getImportStats().reused()).isEqualTo(1);
    }
}
