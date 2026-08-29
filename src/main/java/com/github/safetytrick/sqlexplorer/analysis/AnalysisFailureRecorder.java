package com.github.safetytrick.sqlexplorer.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import org.springframework.stereotype.Component;

/**
 * Keeps what a failing analysis would need to be turned back into a test.
 *
 * <p>Before this, an analyzer failure recorded one string on the profile —
 * {@code "Failure on: SelectColumnAnalyzer message: with items list"} — which
 * says what broke and nothing about how to reproduce it. Turning three such
 * failures into regression tests meant querying the API for the statements,
 * reading them to work out which tables and columns they touched, and
 * hand-writing a {@code SchemaFixture} to match. That reconstruction is the
 * same every time and it is why failures sit unfixed.
 *
 * <p>Everything needed was already in scope at the catch — the method holds the
 * SQL and the schema the analyzers resolved against. It was discarded, not
 * unavailable.
 *
 * <h2>Lifetime</h2>
 *
 * <p>In memory, and deliberately not on the profile. The corpus is rebuilt from
 * scratch on every source load, so these have exactly the same lifetime as the
 * analysis that produced them; persisting them would mean keeping a second
 * record of something recomputed on every import. It also keeps the Avro model
 * and the parquet sink out of it.
 *
 * <p>Bounded, because a pathological source could otherwise fail on every row
 * and this is a field that only ever grows.
 */
@Component
public class AnalysisFailureRecorder {

    /**
     * Enough failures to characterize what a source breaks on, not enough to
     * matter if a source breaks on everything. Distinct statements, so a
     * statement that runs a million times still counts once.
     */
    static final int MAX_CAPTURED = 200;

    /**
     * One analyzer's failure on one statement.
     *
     * @param analyzer  the analyzer class that threw
     * @param message   the root cause message, matching what the profile's
     *                  single reason string reports
     * @param stackTrace the top frames, for the cause rather than the repro —
     *                  the two have different audiences and this one is what
     *                  makes the bug obvious rather than reproducible
     */
    public record AnalyzerFailure(String analyzer, String message, String stackTrace) { }

    /**
     * A failing statement with what is needed to replay it.
     *
     * @param ddl the {@code CREATE TABLE} text for the tables the statement
     *            referenced — what {@code SchemaFixture.fromSql} takes. Only
     *            the referenced tables: the whole repository is large and
     *            carries column names not worth copying around.
     */
    public record CapturedFailure(String sql, String sessionSchema, List<String> ddl,
                                  List<AnalyzerFailure> failures) { }

    private final Map<String, CapturedFailure> byStatement = new LinkedHashMap<>();

    /**
     * Record one analyzer failure against a statement.
     *
     * <p>Accumulates rather than replaces. The profile's single
     * {@code analysisFailureReason} is assigned, so when two analyzers throw
     * the first is silently overwritten and the profile reports one cause where
     * there were two — a reproduction harness built on that field would
     * reproduce one of N bugs. That string is left exactly as it is, because
     * {@code QuerySearchService.ANALYZER_FAILURE_PREFIX} and two report
     * services classify failures by its prefix; this is additive.
     */
    public synchronized void record(String sql, String sessionSchema, SqlSchemaRepository schema,
                                    List<TableReference> tables, String analyzer, Throwable thrown) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        AnalyzerFailure failure = new AnalyzerFailure(analyzer, rootMessage(thrown), topFrames(thrown));

        CapturedFailure existing = byStatement.get(sql);
        if (existing != null) {
            List<AnalyzerFailure> combined = new ArrayList<>(existing.failures());
            combined.add(failure);
            byStatement.put(sql, new CapturedFailure(sql, existing.sessionSchema(), existing.ddl(), combined));
            return;
        }
        if (byStatement.size() >= MAX_CAPTURED) {
            return;
        }
        byStatement.put(sql, new CapturedFailure(sql, sessionSchema, ddlFor(schema, tables), List.of(failure)));
    }

    /** Everything captured since the last {@link #clear()}, in first-seen order. */
    public synchronized List<CapturedFailure> captured() {
        return List.copyOf(byStatement.values());
    }

    public synchronized CapturedFailure forStatement(String sql) {
        return byStatement.get(sql);
    }

    /** Dropped when a source reloads, since analysis is about to run again. */
    public synchronized void clear() {
        byStatement.clear();
    }

    /**
     * {@code CREATE TABLE} text for each referenced table, which is what
     * {@code SchemaFixture.fromSql(ddl)} consumes — so writing the regression
     * test is a paste rather than an investigation.
     *
     * <p>Best effort. Deriving "referenced" is itself analysis and the analysis
     * is what just failed, so the table list may be partial or empty; a fixture
     * with some of the tables still saves most of the work, and one with none
     * still carries the statement.
     */
    private static List<String> ddlFor(SqlSchemaRepository schema, List<TableReference> tables) {
        if (schema == null || tables == null || tables.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> ddl = new ArrayList<>();
        for (TableReference table : tables) {
            if (table == null || !seen.add(table.getFullName())) {
                continue;
            }
            try {
                TableStructure structure = schema.getTable(table);
                if (structure == null) {
                    continue;
                }
                String create = schema.getCreateStatement(structure);
                if (create != null && !create.isBlank()) {
                    ddl.add(create);
                }
            } catch (RuntimeException e) {
                // a schema that cannot answer is not a reason to lose the
                // statement, which is the more valuable half
                ddl.add("/* DDL unavailable for " + table.getFullName() + ": " + rootMessage(e) + " */");
            }
        }
        return List.copyOf(ddl);
    }

    private static String rootMessage(Throwable e) {
        String message = e == null ? "unknown" : e.toString();
        while (e != null) {
            if (e.getMessage() != null) {
                message = e.getMessage();
            }
            e = e.getCause();
        }
        return message;
    }

    /** The top frames only — enough to see where it threw, not a wall of Spring. */
    private static String topFrames(Throwable thrown) {
        if (thrown == null) {
            return "";
        }
        StackTraceElement[] frames = thrown.getStackTrace();
        StringBuilder out = new StringBuilder(thrown.toString());
        for (int i = 0; i < Math.min(frames.length, 8); i++) {
            out.append("\n\tat ").append(frames[i]);
        }
        return out.toString();
    }
}
