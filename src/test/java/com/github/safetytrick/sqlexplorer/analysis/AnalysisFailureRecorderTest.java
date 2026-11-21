package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.schema.LoadedSchema;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisFailureRecorderTest {

    private final AnalysisFailureRecorder recorder = new AnalysisFailureRecorder();

    @Test
    void keepsEveryAnalyzersFailureOnOneStatementRatherThanTheLast() {
        recorder.record("select now", null, null, List.of(), "SelectColumnAnalyzer", new RuntimeException("first"));
        recorder.record("select now", null, null, List.of(), "JoinColumnAnalyzer", new RuntimeException("second"));

        assertThat(recorder.captured()).hasSize(1);
        assertThat(recorder.forStatement("select now").failures())
            .extracting(AnalysisFailureRecorder.AnalyzerFailure::analyzer)
            .containsExactly("SelectColumnAnalyzer", "JoinColumnAnalyzer");
    }

    @Test
    void reportsTheRootCauseMessageTheProfileAlsoShows() {
        recorder.record("select now", null, null, List.of(), "SelectColumnAnalyzer",
            new RuntimeException("outer", new IllegalStateException("missing schema")));

        assertThat(recorder.forStatement("select now").failures().getFirst().message())
            .isEqualTo("missing schema");
    }

    @Test
    void capturesCreateTableTextForTheReferencedTables() throws JSQLParserException {
        LoadedSchema schema = schemaWith("orders");

        recorder.record("select id from orders", "app", schema,
            List.of(TableReference.newTableReference("app", "orders")),
            "SelectColumnAnalyzer", new RuntimeException("boom"));

        assertThat(recorder.forStatement("select id from orders").ddl())
            .anySatisfy(ddl -> assertThat(ddl).contains("orders"));
    }

    @Test
    void keepsTheStatementEvenWhenNoTablesCouldBeResolved() {
        recorder.record("select now", "app", null, List.of(), "SelectColumnAnalyzer", new RuntimeException("boom"));

        AnalysisFailureRecorder.CapturedFailure captured = recorder.forStatement("select now");
        assertThat(captured.sql()).isEqualTo("select now");
        assertThat(captured.ddl()).isEmpty();
    }

    @Test
    void stopsCapturingNewStatementsAtItsCeiling() {
        for (int i = 0; i < AnalysisFailureRecorder.MAX_CAPTURED + 50; i++) {
            recorder.record("select " + i, null, null, List.of(), "A", new RuntimeException("boom"));
        }

        assertThat(recorder.captured()).hasSize(AnalysisFailureRecorder.MAX_CAPTURED);
    }

    @Test
    void stillRecordsFurtherFailuresForAStatementItAlreadyHas() {
        for (int i = 0; i < AnalysisFailureRecorder.MAX_CAPTURED; i++) {
            recorder.record("select " + i, null, null, List.of(), "A", new RuntimeException("boom"));
        }

        recorder.record("select 0", null, null, List.of(), "B", new RuntimeException("boom"));

        assertThat(recorder.forStatement("select 0").failures()).hasSize(2);
    }

    @Test
    void ignoresAStatementWithNoText() {
        recorder.record(null, null, null, List.of(), "A", new RuntimeException("boom"));
        recorder.record("  ", null, null, List.of(), "A", new RuntimeException("boom"));

        assertThat(recorder.captured()).isEmpty();
    }

    @Test
    void clearsWhenTheSourceReloads() {
        recorder.record("select now", null, null, List.of(), "A", new RuntimeException("boom"));
        recorder.clear();

        assertThat(recorder.captured()).isEmpty();
    }

    private static LoadedSchema schemaWith(String table) throws JSQLParserException {
        return new LoadedSchema(List.of(TableStructure.fromCreateTable(
            "CREATE TABLE `" + table + "` (`id` int, PRIMARY KEY (`id`))", "app").build()));
    }
}
