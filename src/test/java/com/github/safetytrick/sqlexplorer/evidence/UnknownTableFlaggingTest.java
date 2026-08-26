package com.github.safetytrick.sqlexplorer.evidence;

import java.util.List;

import com.github.safetytrick.sqlexplorer.analysis.RegexTableExtractor;
import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A pre-scan that misreads a statement should fail loudly.
 *
 * <p>The regex extractor reads everything in {@code ignoredFirstTokens} and
 * nothing corroborates it, so `ON UPDATE NOW` became a table called `now` and
 * was only noticed when it appeared in the schema tree as a table that does
 * not exist. Recording the unrecognised name as a failure reason turns the
 * next one of those into something a test can assert on.
 */
class UnknownTableFlaggingTest {

    private static SqlProfile.Builder profile() {
        return SqlProfile.newBuilder()
            .setQuery("CREATE TABLE `t` ( `a` DATETIME )")
            .setType("CREATE")
            .setDigest("d")
            .setNormalizedDigest("d")
            .setSchemaName("tdb")
            .setQueryActivityStatus(QueryActivityStatus.ACTIVE)
            .setRuntimeMetrics(null)
            .setAnalysisSkipped(true);
    }

    @Test
    void anUnknownExtractedTableMarksAnalysisFailed() {
        SqlProfile.Builder builder = profile();

        SqlEvidenceInputService.flagUnknownTables(builder,
            List.of(TableReference.fromString("tdb.now")));
        SqlProfile built = builder.completeBuild("tdb");

        assertThat(built.getAnalysisFailureReason())
            .startsWith(RegexTableExtractor.UNKNOWN_TABLE_REASON)
            .contains("tdb.now");
        // complete() derives the flag from the reason, so the two cannot drift
        assertThat(built.getAnalysisFailed()).isTrue();
        assertThat(built.getAnalysisSkipped()).as("still a skipped statement").isTrue();
    }

    @Test
    void everyUnknownNameIsNamedInTheReason() {
        SqlProfile.Builder builder = profile();

        SqlEvidenceInputService.flagUnknownTables(builder, List.of(
            TableReference.fromString("tdb.now"), TableReference.fromString("tdb.cascade")));

        assertThat(builder.getAnalysisFailureReason()).contains("tdb.now", "tdb.cascade");
    }

    /**
     * On the parse-failure path the parser's own message is written first and
     * is the better explanation; this must not trade that cause for a symptom.
     * `analysisFailureReason` has several writers and the last one wins, which
     * is why the ordering is asserted rather than assumed.
     */
    @Test
    void aParseFailureReasonIsNotOverwritten() {
        SqlProfile.Builder builder = profile().setAnalysisFailureReason("Encountered unexpected token: `");

        SqlEvidenceInputService.flagUnknownTables(builder,
            List.of(TableReference.fromString("tdb.now")));

        assertThat(builder.getAnalysisFailureReason()).isEqualTo("Encountered unexpected token: `");
    }

    @Test
    void aStatementWhoseTablesAllExistIsUntouched() {
        SqlProfile.Builder builder = profile();

        SqlEvidenceInputService.flagUnknownTables(builder, List.of());
        SqlProfile built = builder.completeBuild("tdb");

        assertThat(built.getAnalysisFailureReason()).isNull();
        assertThat(built.getAnalysisFailed()).isFalse();
    }
}
