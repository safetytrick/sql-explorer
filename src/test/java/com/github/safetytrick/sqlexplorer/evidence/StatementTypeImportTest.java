package com.github.safetytrick.sqlexplorer.evidence;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.analysis.IgnoredSchemas;
import com.github.safetytrick.sqlexplorer.analysis.SchemaFixture;
import com.github.safetytrick.sqlexplorer.analysis.parser.NormalizedQueryDigest;
import com.github.safetytrick.sqlexplorer.analysis.parser.QueryNormalizer;
import com.github.safetytrick.sqlexplorer.analysis.parser.SqlParser;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.evidence.SqlEvidenceInputService.AnalysisContext;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetConfig;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetSink;
import com.github.safetytrick.sqlexplorer.evidence.source.MeteredSql;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlInput;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A loaded statement's type is its operation and its isDml says whether that
 * writes, on a fresh analysis, on an unparsed statement, and on reuse
 * (sqlx-a9h).
 */
class StatementTypeImportTest {

    @TempDir
    Path directory;

    private static final String CTE_DELETE = "WITH `gone` AS ( SELECT `id` FROM `account` WHERE `status` = ? ) "
        + "DELETE FROM `account` WHERE `id` IN ( SELECT `id` FROM `gone` )";
    /** Cut off by Performance Schema inside the CTE, as a long one is. */
    private static final String TRUNCATED = "WITH `a` AS ( SELECT `id` FROM `account` WHERE `status` = ? AND `id` IN ( ...";

    private SqlEvidenceInputService service;
    private final SqlSource source = new SqlSource() {
        @Override public Stream<SqlInput> getSql() { return Stream.empty(); }
        @Override public Instant getCreatedOn() { return Instant.parse("2026-11-11T12:00:00Z"); }
        @Override public String getSourceRef() { return "shard1"; }
    };

    @BeforeEach
    void setUp() throws JSQLParserException {
        ParquetConfig config = new ParquetConfig();
        config.setFilename(directory.resolve("capture.parquet").toString());
        SchemaFixture fixture = SchemaFixture.fromSql(
            "create table account (id bigint not null, status varchar(16), primary key (id));");
        service = new SqlEvidenceInputService(false, new ParquetSink(config), fixture.analysisServiceWithSchemaInference(),
            new NormalizedQueryDigest(new QueryNormalizer(), new SqlParser()), new SimpleMeterRegistry(),
            new IgnoredSchemas());
    }

    private static MeteredSql row(String digest, String query, long rowsAffected) {
        MeteredSql row = new MeteredSql();
        row.setQuery(query);
        row.setSchemaName("app_main");
        row.setDigest(digest);
        row.setExecuted(10);
        row.setRowsAffected(rowsAffected);
        row.setTotalTime(BigDecimal.ONE);
        row.setMaxTime(BigDecimal.ONE);
        row.setAverageTime(BigDecimal.ONE);
        row.setLockTime(BigDecimal.ZERO);
        row.setFirstSeen(LocalDateTime.parse("2026-11-01T00:00:00"));
        row.setLastSeen(LocalDateTime.parse("2026-11-11T00:00:00"));
        return row;
    }

    private SqlProfile load(AnalysisContext context, MeteredSql row) {
        SqlRepository repository = service.fromPerformanceSchema(source,
            Stream.of(new SqlInput(0, row, source)), null, context);
        List<SqlProfile> profiles = repository.getAllAnalyzedSql().toList();
        assertThat(profiles).hasSize(1);
        return profiles.getFirst();
    }

    /** It matched nothing, so rows affected alone would call it a read. */
    @Test
    void aCteDeleteThatChangedNothingIsADeleteAndDml() {
        SqlProfile profile = load(AnalysisContext.ANALYSE_EVERYTHING, row("d1", CTE_DELETE, 0));

        assertThat(profile.getAnalysisFailed()).isFalse();
        assertThat(profile.getType()).isEqualTo("DELETE");
        assertThat(profile.getIsDml()).isTrue();
    }

    @Test
    void anUnreadableStatementIsUnknownAndDmlOnlyIfItChangedRows() {
        SqlProfile wrote = load(AnalysisContext.ANALYSE_EVERYTHING, row("t1", TRUNCATED, 4));
        assertThat(wrote.getType()).isEqualTo("UNKNOWN");
        assertThat(wrote.getIsDml()).isTrue();

        SqlProfile quiet = load(AnalysisContext.ANALYSE_EVERYTHING, row("t2", TRUNCATED, 0));
        assertThat(quiet.getType()).isEqualTo("UNKNOWN");
        assertThat(quiet.getIsDml()).isFalse();
    }

    /** A write that changes one row per run is DML: the old threshold was more than one. */
    @Test
    void aWriteOfOneRowIsDml() {
        SqlProfile profile = load(AnalysisContext.ANALYSE_EVERYTHING,
            row("u1", "UPDATE `account` SET `status` = ? WHERE `id` = ?", 1));

        assertThat(profile.getIsDml()).isTrue();
        assertThat(load(AnalysisContext.ANALYSE_EVERYTHING,
            row("s1", "SELECT `id` FROM `account` WHERE `id` = ?", 0)).getIsDml()).isFalse();
    }

    /** Reuse brings the parsed type; isDml is derived from it again, not copied. */
    @Test
    void aReusedAnalysisKeepsTheParsedTypeAndIsDml() {
        SqlProfile prior = load(AnalysisContext.ANALYSE_EVERYTHING, row("d1", CTE_DELETE, 0));

        SqlProfile reused = load(new AnalysisContext(AnalysisReuse.of(List.of(prior)), false),
            row("d1", CTE_DELETE, 0));

        assertThat(reused.getType()).isEqualTo("DELETE");
        assertThat(reused.getIsDml()).isTrue();
    }
}
