package com.github.safetytrick.sqlexplorer.profiling;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.github.safetytrick.sqlexplorer.profiling.ProfilePlan.ProfiledColumn;

final class ProfileSql {

    record Statement(String sql, List<Object> parameters) {

        String display() {
            StringBuilder out = new StringBuilder();
            int next = 0;
            for (int i = 0; i < sql.length(); i++) {
                char c = sql.charAt(i);
                if (c == '?' && next < parameters.size()) {
                    out.append(literal(parameters.get(next++)));
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }

        private static String literal(Object value) {
            if (value instanceof Placeholder placeholder) {
                return placeholder.text();
            }
            if (value == null) {
                return "NULL";
            }
            if (value instanceof Number) {
                return value.toString();
            }
            return "'" + value.toString().replace("\\", "\\\\").replace("'", "''") + "'";
        }
    }

    record Placeholder(String text) { }

    enum WindowKind {
        HEAD,
        FROM,
        AFTER,
        TAIL
    }

    record Window(WindowKind kind, Object bound, Object below, int rows, String label) {

        Window(WindowKind kind, Object bound, int rows, String label) {
            this(kind, bound, null, rows, label);
        }
    }

    private final ProfilePlan plan;
    private final String table;
    private final List<String> selected;

    ProfileSql(ProfilePlan plan) {
        this.plan = plan;
        this.table = quote(plan.schema()) + "." + quote(plan.table());
        Set<String> columns = new LinkedHashSet<>(plan.key());
        plan.columns().forEach(c -> columns.add(c.name()));
        this.selected = List.copyOf(columns);
    }

    Statement bounds() {
        String key = quote(plan.key().getFirst());
        return new Statement("SELECT MIN(" + key + ") AS min_key, MAX(" + key + ") AS max_key FROM " + table,
            List.of());
    }

    Statement window(Window window) {
        String columns = selected.stream().map(ProfileSql::quote).collect(Collectors.joining(", "));
        String ascending = plan.key().stream().map(ProfileSql::quote).collect(Collectors.joining(", "));
        String descending = plan.key().stream().map(k -> quote(k) + " DESC").collect(Collectors.joining(", "));
        String key = quote(plan.key().getFirst());
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ").append(columns).append(" FROM ").append(table);
        switch (window.kind()) {
            case HEAD -> sql.append(" ORDER BY ").append(ascending);
            case FROM -> {
                sql.append(" WHERE ").append(key).append(" >= ?");
                parameters.add(window.bound());
                if (window.below() != null) {
                    sql.append(" AND ").append(key).append(" < ?");
                    parameters.add(window.below());
                }
                sql.append(" ORDER BY ").append(ascending);
            }
            case AFTER -> {
                if (window.bound() != null) {
                    sql.append(" WHERE ").append(key).append(" > ?");
                    parameters.add(window.bound());
                }
                sql.append(" ORDER BY ").append(ascending);
            }
            case TAIL -> {
                if (window.bound() != null) {
                    sql.append(" WHERE ").append(key).append(" > ?");
                    parameters.add(window.bound());
                }
                sql.append(" ORDER BY ").append(descending);
            }
        }
        sql.append(" LIMIT ").append(window.rows());
        return new Statement(sql.toString(), parameters);
    }

    Statement aggregate(Window window) {
        Statement source = window(window);
        boolean single = plan.key().size() == 1;
        String key = single ? "w." + quote(plan.key().getFirst()) : null;
        String keyText = single ? key : "CONCAT_WS('/', " + plan.key().stream().map(k -> "w." + quote(k))
            .collect(Collectors.joining(", ")) + ")";
        String keyOrder = plan.key().stream().map(k -> "w." + quote(k)).collect(Collectors.joining(", "));
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS n_rows");
        sql.append(single ? ", MIN(" + key + ") AS min_key, MAX(" + key + ") AS max_key"
            : ", NULL AS min_key, NULL AS max_key");
        for (int i = 0; i < plan.columns().size(); i++) {
            ProfiledColumn column = plan.columns().get(i);
            String c = "w." + quote(column.name());
            String p = "c" + i;
            sql.append(",\n  SUM(").append(c).append(" IS NULL) AS ").append(p).append("_nulls");
            sql.append(", MAX(LENGTH(").append(c).append(")) AS ").append(p).append("_bytes");
            sql.append(", COUNT(DISTINCT ").append(c).append(") AS ").append(p).append("_distinct");
            sql.append(", SUM(REGEXP_LIKE(").append(c).append(", ?, 'c')) AS ").append(p).append("_non_ascii");
            parameters.add(Signature.NON_ASCII);
            for (Signature signature : column.signatures()) {
                int s = signature.ordinal();
                sql.append(",\n  SUM(REGEXP_LIKE(").append(c).append(", ?, 'c')) AS ").append(p).append("_s").append(s);
                parameters.add(signature.pattern());
                sql.append(", SUBSTRING_INDEX(GROUP_CONCAT(IF(REGEXP_LIKE(").append(c).append(", ?, 'c'), ")
                    .append(keyText).append(", NULL) ORDER BY ").append(keyOrder).append(" SEPARATOR ','), ',', ")
                    .append(ProfilePlan.IDS_PER_SIGNATURE).append(") AS ").append(p).append("_k").append(s);
                parameters.add(signature.pattern());
            }
        }
        sql.append("\nFROM (").append(source.sql()).append(") AS w");
        parameters.addAll(source.parameters());
        return new Statement(sql.toString(), parameters);
    }

    /**
     * Column and pattern are both converted to utf8mb4, so a latin1 column and
     * the patterns compare under one collation whatever the connection's is.
     */
    Statement characters(Window window, ProfiledColumn column, List<Signature> signatures) {
        Statement source = window(window);
        List<Object> parameters = new ArrayList<>(source.parameters());
        String c = "CONVERT(w." + quote(column.name()) + " USING utf8mb4)";
        StringBuilder patterns = new StringBuilder();
        for (Signature signature : signatures) {
            patterns.append(patterns.isEmpty() ? "SELECT ? AS sig, ? AS pat" : " UNION ALL SELECT ?, ?");
            parameters.add(signature.name());
            parameters.add(signature.pattern());
        }
        String sql = "SELECT p.sig, REGEXP_SUBSTR(" + c + ", CONVERT(p.pat USING utf8mb4), 1, 1, 'c') AS matched, COUNT(*) AS n_rows\n"
            + "FROM (" + source.sql() + ") AS w\n"
            + "JOIN (" + patterns + ") AS p ON REGEXP_LIKE(" + c + ", CONVERT(p.pat USING utf8mb4), 'c')\n"
            + "GROUP BY p.sig, matched ORDER BY n_rows DESC LIMIT 200";
        return new Statement(sql, parameters);
    }

    static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
