package com.github.safetytrick.sqlexplorer.evidence;

import java.util.List;

import com.github.safetytrick.sqlexplorer.analysis.RegexTableExtractor;
import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
