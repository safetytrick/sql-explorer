package com.github.safetytrick.sqlexplorer.evidence;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Performance Schema's digest-overflow row is not a statement (sqlx-ugf).
 *
 * <p>Once {@code performance_schema_digests_size} is reached, every statement
 * without a digest row of its own is counted in one row whose digest, schema
 * and text are all NULL. On two production primaries that row was read
 * carrying another statement's normalized text, which got it past the null-text
 * check and into an Avro rejection of its null digest. Its executions are
 * already reported as lost under "Room to record"; here it is simply skipped.
 */
class DigestOverflowRowTest {

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

    private static MeteredSql row(String digest, String query, long executions) {
        MeteredSql row = new MeteredSql();
        row.setQuery(query);
        row.setDigest(digest);
        row.setExecuted(executions);
        row.setTotalTime(BigDecimal.ONE);
        row.setMaxTime(BigDecimal.ONE);
        row.setAverageTime(BigDecimal.ONE);
        row.setLockTime(BigDecimal.ZERO);
        row.setFirstSeen(LocalDateTime.parse("2026-08-18T21:22:49.923734"));
        row.setLastSeen(LocalDateTime.parse("2026-10-19T16:44:15.259603"));
        return row;
    }

    @Test
    void theOverflowRowIsSkippedAndTheRealStatementLoads() {
        ParquetConfig config = new ParquetConfig();
        config.setFilename(directory.resolve("capture.parquet").toString());
        SqlParser parser = new SqlParser();
        SqlEvidenceInputService service = new SqlEvidenceInputService(false, new ParquetSink(config),
            new SqlAnalysisService(), new NormalizedQueryDigest(new QueryNormalizer(), parser),
            new SimpleMeterRegistry(), new IgnoredSchemas());

        SqlSource source = new SqlSource() {
            @Override public Stream<SqlInput> getSql() { return Stream.empty(); }
            @Override public Instant getCreatedOn() { return Instant.parse("2026-10-19T16:44:47Z"); }
            @Override public String getSourceRef() { return "shard2"; }
        };
        String text = "SELECT /*+ MAX_EXECUTION_TIME (?) */ `t0_` . `id` FROM `orders` `t0_` WHERE `t0_` . `customer_id` = ?";

        SqlRepository repository = service.fromPerformanceSchema(source, Stream.of(
            new SqlInput(0, row("7e2b9c4a1d6f8e3b5c0a2d4f6e8b1c3a5d7f9e0b2c4d6f8a1b3c5e7d9f0a2b4c", text, 546_848_633L),
                source),
            new SqlInput(1, row(null, text, 2_228_449L), source)), null);

        assertThat(repository.getAllAnalyzedSql())
            .extracting(profile -> profile.getRuntimeMetrics().getExecuted())
            .containsExactly(546_848_633L);
        assertThat(log.list).filteredOn(event -> event.getLevel() == Level.ERROR)
            .as("skipped, not failed").isEmpty();
    }
}
