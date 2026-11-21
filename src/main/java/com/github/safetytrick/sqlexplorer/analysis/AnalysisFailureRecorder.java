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

@Component
public class AnalysisFailureRecorder {

    static final int MAX_CAPTURED = 200;

    public record AnalyzerFailure(String analyzer, String message, String stackTrace) { }

    public record CapturedFailure(String sql, String sessionSchema, List<String> ddl,
                                  List<AnalyzerFailure> failures) { }

    private final Map<String, CapturedFailure> byStatement = new LinkedHashMap<>();

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

    public synchronized List<CapturedFailure> captured() {
        return List.copyOf(byStatement.values());
    }

    public synchronized CapturedFailure forStatement(String sql) {
        return byStatement.get(sql);
    }

    public synchronized void clear() {
        byStatement.clear();
    }

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
