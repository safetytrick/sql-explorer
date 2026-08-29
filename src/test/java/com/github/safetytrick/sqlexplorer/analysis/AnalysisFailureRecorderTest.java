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

    /**
     * The defect this exists for. The profile's single
     * {@code analysisFailureReason} is *assigned*, so when two analyzers throw
     * on one statement the first is silently overwritten and the profile
     * reports one cause where there were two. A reproduction harness built on
     * that field would reproduce one of N bugs.
     */
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

    /**
     * The DDL is the half that makes a captured failure a test rather than a
     * bug report — {@code SchemaFixture.fromSql(ddl)} takes exactly this.
     */
    @Test
    void capturesCreateTableTextForTheReferencedTables() throws JSQLParserException {
        LoadedSchema schema = schemaWith("orders");

        recorder.record("select id from orders", "app", schema,
            List.of(TableReference.newTableReference("app", "orders")),
            "SelectColumnAnalyzer", new RuntimeException("boom"));

        assertThat(recorder.forStatement("select id from orders").ddl())
            .anySatisfy(ddl -> assertThat(ddl).contains("orders"));
    }

    /**
     * Deriving "referenced" is itself analysis, and the analysis is what just
     * failed — so an empty table list is normal, and the statement is still
     * worth keeping without it.
     */
    @Test
    void keepsTheStatementEvenWhenNoTablesCouldBeResolved() {
        recorder.record("select now", "app", null, List.of(), "SelectColumnAnalyzer", new RuntimeException("boom"));

        AnalysisFailureRecorder.CapturedFailure captured = recorder.forStatement("select now");
        assertThat(captured.sql()).isEqualTo("select now");
        assertThat(captured.ddl()).isEmpty();
    }

    /** A source that fails on every row must not grow this without bound. */
    @Test
    void stopsCapturingNewStatementsAtItsCeiling() {
        for (int i = 0; i < AnalysisFailureRecorder.MAX_CAPTURED + 50; i++) {
            recorder.record("select " + i, null, null, List.of(), "A", new RuntimeException("boom"));
        }

        assertThat(recorder.captured()).hasSize(AnalysisFailureRecorder.MAX_CAPTURED);
    }

    /** ...but a statement already captured keeps accumulating past the ceiling. */
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

    /** Analysis reruns on every source load, so the capture starts over with it. */
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
