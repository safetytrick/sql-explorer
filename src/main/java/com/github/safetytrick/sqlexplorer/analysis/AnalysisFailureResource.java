package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the analyzers failed on, and enough to replay it.
 *
 * <p>The point of {@link #fixtures()} is that its output is a paste. The
 * analyzer tests are written as {@code SchemaFixture.fromSql(ddl)} plus
 * {@code .analyze(sql)}, so a captured failure rendered in that shape turns
 * "reproduce this production failure" from an investigation into a copy.
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Analysis failures")
@RestController
class AnalysisFailureResource {

    private final AnalysisFailureRecorder recorder;

    AnalysisFailureResource(AnalysisFailureRecorder recorder) {
        this.recorder = recorder;
    }

    /** The captured failures as data, for anything that wants to render its own view. */
    @GetMapping("/analysis-failures")
    public List<AnalysisFailureRecorder.CapturedFailure> failures() {
        return recorder.captured();
    }

    /**
     * The same failures rendered as Java test fixtures, ready to paste into a
     * new test method.
     *
     * <p>Plain text rather than JSON because the consumer is a person with an
     * editor open, and a JSON-escaped Java string with {@code \\n} in it is not
     * something anyone can paste.
     */
    @GetMapping(path = "/analysis-failures/fixtures", produces = "text/plain")
    public String fixtures() {
        List<AnalysisFailureRecorder.CapturedFailure> captured = recorder.captured();
        if (captured.isEmpty()) {
            return "// no analyzer failures captured since the last source load\n";
        }

        StringBuilder out = new StringBuilder();
        out.append("// ").append(captured.size())
            .append(" statement(s) an analyzer threw on, rendered as SchemaFixture tests.\n")
            .append("// Paste into an analyzer test; each is a failing case until the analyzer handles it.\n\n");

        int n = 0;
        for (AnalysisFailureRecorder.CapturedFailure failure : captured) {
            out.append(render(failure, ++n));
        }
        return out.toString();
    }

    private static String render(AnalysisFailureRecorder.CapturedFailure failure, int index) {
        StringBuilder out = new StringBuilder();

        for (AnalysisFailureRecorder.AnalyzerFailure thrown : failure.failures()) {
            out.append("// ").append(thrown.analyzer()).append(": ").append(thrown.message()).append('\n');
        }
        if (failure.sessionSchema() != null) {
            out.append("// session schema: ").append(failure.sessionSchema()).append('\n');
        }
        if (failure.ddl().isEmpty()) {
            // Saying so beats an empty fromSql("") that looks like it should work.
            out.append("// no DDL captured — the analysis that identifies referenced tables is\n")
                .append("// the analysis that failed, so the table list may be empty. Add the\n")
                .append("// CREATE TABLE text by hand, or use SchemaFixture.allFixtures().\n");
        }

        out.append("@Test\n")
            .append("void analyzerFailure").append(index).append("() throws JSQLParserException {\n")
            .append("    SchemaFixture fixture = SchemaFixture.fromSql(\"\"\"\n");
        for (String ddl : failure.ddl()) {
            out.append(indent(ddl.strip().endsWith(";") ? ddl.strip() : ddl.strip() + ";", "        ")).append('\n');
        }
        out.append("        \"\"\");\n\n")
            .append("    SqlProfile profile = fixture.analyze(\"\"\"\n")
            .append(indent(failure.sql().strip(), "        ")).append('\n')
            .append("        \"\"\");\n\n")
            .append("    assertThat(profile.getAnalysisFailureReason()).isNull();\n")
            .append("}\n\n");
        return out.toString();
    }

    private static String indent(String text, String prefix) {
        return text.lines().map(line -> prefix + line).reduce((a, b) -> a + "\n" + b).orElse(prefix);
    }
}
