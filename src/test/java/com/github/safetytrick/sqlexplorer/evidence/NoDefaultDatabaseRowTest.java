package com.github.safetytrick.sqlexplorer.evidence;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.safetytrick.sqlexplorer.analysis.IgnoredSchemas;
import com.github.safetytrick.sqlexplorer.analysis.SqlAnalysisService;
import com.github.safetytrick.sqlexplorer.analysis.parser.NormalizedQueryDigest;
import com.github.safetytrick.sqlexplorer.analysis.parser.QueryNormalizer;
import com.github.safetytrick.sqlexplorer.analysis.parser.SqlParser;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetConfig;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetSink;
import com.github.safetytrick.sqlexplorer.evidence.source.MeteredSql;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlInput;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.schema.LoadedSchema;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A statement run with no default database is analysed, not dropped
 * (sqlx-4wm). Production's digest tables hold such statements — sql-explorer's
 * own capture query among them — and every one threw from the unknown-table
 * check and was lost from the corpus, then re-analysed and lost again on every
 * pull, since a dropped row is never captured for reuse.
 */
class NoDefaultDatabaseRowTest {

    @TempDir
    Path directory;

    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(SqlEvidenceInputService.class);

    @BeforeEach
    void listen() {
        log.start();
        serviceLogger.addAppender(log);
    }

    @AfterEach
    void stopListening() {
        serviceLogger.detachAppender(log);
    }

    @Test
    void theCaptureQueryWithNoDatabaseBecomesAProfile() {
        ParquetConfig config = new ParquetConfig();
        config.setFilename(directory.resolve("capture.parquet").toString());
        SqlEvidenceInputService service = new SqlEvidenceInputService(false, new ParquetSink(config),
            new SqlAnalysisService(), new NormalizedQueryDigest(new QueryNormalizer(), new SqlParser()),
            new SimpleMeterRegistry(), new IgnoredSchemas());
        SqlSource source = new SqlSource() {
            @Override public Stream<SqlInput> getSql() { return Stream.empty(); }
            @Override public Instant getCreatedOn() { return Instant.parse("2026-10-19T20:00:00Z"); }
            @Override public String getSourceRef() { return "shard1"; }
        };
        MeteredSql row = new MeteredSql();
        row.setQuery("SELECT QUERY , `db` , `digest` FROM `sys` . `x$statement_analysis`");
        row.setSchemaName(null);
        row.setDigest("9a8b7c6d5e4f30211203f4e5d6c7b8a99a8b7c6d5e4f30211203f4e5d6c7b8a9");
        row.setExecuted(12);
        row.setTotalTime(BigDecimal.ONE);
        row.setMaxTime(BigDecimal.ONE);
        row.setAverageTime(BigDecimal.ONE);
        row.setLockTime(BigDecimal.ZERO);
        row.setFirstSeen(LocalDateTime.parse("2026-10-19T16:33:23"));
        row.setLastSeen(LocalDateTime.parse("2026-10-19T19:54:30"));
        // a loaded schema, so the unknown-table check runs — the path that threw
        LoadedSchema schema = new LoadedSchema(List.of(new TableStructure("app_production", "users", "TABLE", null,
            List.of(), List.of(), List.of(), null, null)));

        SqlRepository repository = service.fromPerformanceSchema(source, Stream.of(new SqlInput(0, row, source)), schema);

        assertThat(repository.getAllAnalyzedSql()).hasSize(1);
        assertThat(log.list).filteredOn(event -> event.getLevel() == Level.ERROR).isEmpty();
    }
}
