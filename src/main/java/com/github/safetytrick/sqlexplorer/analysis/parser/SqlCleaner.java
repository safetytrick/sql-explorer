package com.github.safetytrick.sqlexplorer.analysis.parser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
class SqlCleaner {

    public CleanedSql cleanSql(String sql) {
        String beforeMundane = sql;

        sql = sql.replace("...", "?")
            // the jsqlparser chokes on these
            .replace("@?", "@a")
            .replace("AS ?", "")
            .replace(") ?", ")")
            .replace("` ?", "`")
            .replace("+ ? SQL_TSI_DAY", "SQL_TSI_DAY")
            .replace("INTERVAL ?", "INTERVAL 0")
            .replace("INTERVAL @a SQL_TSI_DAY", "INTERVAL 1 SQL_TSI_DAY")
            .replace("LOCK IN SHARE MODE", "")
            .replace("DISTINCTROW", "DISTINCT")
            // jsqlparser 5.x parses the BINARY operator natively; the old rewrite to
            // CAST(...) produced invalid SQL (CAST without AS type) and is no longer needed.
            .replace(" RLIKE ", " LIKE ")
            .replace("SEPARATOR ?", "SEPARATOR '|'")
            .replace("AS CHARACTER (?)", "AS CHARACTER ()")
            .replaceAll("= TRUE(\\s|$)", "= 1 ")
            .replaceAll("\\?(\\s+\\?)+", "?")
            .replaceAll("\\s`\\s+", " `");

        sql = collapseIfStatements(sql);
        sql = rewriteInsertRowAlias(sql);

        String beforeTruncated = sql;

        sql = sql.replaceFirst("[.,]\\s*$", "")
            .replaceFirst("=\\s*$", "= 1")
            .replaceFirst("\\s(AND|OR|ON|WHERE)\\s*\\(?\\s*$", "");

        return new CleanedSql(sql, !beforeTruncated.equals(sql), !sql.equals(beforeMundane));
    }


    /**
     * The row alias in {@code VALUES (…) AS new ON DUPLICATE KEY UPDATE
     * col = new.col}, rewritten to the older {@code col = VALUES(col)} it
     * replaced.
     *
     * <p>MySQL 8.0.19 introduced this form and deprecated {@code VALUES()};
     * jsqlparser 5.3 — the newest release — accepts only the old one. That is
     * eight profiles in the corpus, all INSERTs into `search`, and they are
     * the <em>only</em> user-schema statements the analyzer genuinely loses
     * (sqlx-a1i). Everything else it declines to parse is a statement nobody
     * wanted parsed.
     *
     * <p>Rewriting rather than merely deleting {@code AS new} matters. Dropping
     * the alias alone leaves {@code col = `new`.`col`} behind, which parses —
     * and then the analyzers resolve {@code new} as a table, inventing a table
     * reference that does not exist. {@code VALUES(col)} is what the alias is
     * shorthand for, so the rewritten statement means what the original meant.
     *
     * <p>Both halves are required to fire together: an alias is only ever
     * removed when its references can be rewritten in the same pass.
     */
    private String rewriteInsertRowAlias(String sql) {
        Matcher alias = ROW_ALIAS.matcher(sql);
        if (!alias.find()) {
            return sql;
        }
        String name = alias.group("alias");
        // digest text spaces the qualifier out — `new` . `col` — and the alias
        // is declared in one case and referenced in another
        Pattern qualified = Pattern.compile(
            "`?" + Pattern.quote(name) + "`?\\s*\\.\\s*`?(\\w+)`?",
            Pattern.CASE_INSENSITIVE);

        String head = sql.substring(0, alias.start());
        String tail = qualified.matcher(sql.substring(alias.end())).replaceAll("VALUES(`$1`)");
        return head + " " + tail;
    }

    /**
     * {@code AS <alias>} sitting between the values list and ON DUPLICATE KEY
     * UPDATE. Both ends are zero-width — a lookbehind for the closing paren
     * and a lookahead for ON DUPLICATE — so the match is exactly the alias
     * clause, and a plain {@code AS} elsewhere in an INSERT is never touched.
     */
    private static final Pattern ROW_ALIAS = Pattern.compile(
        "(?<=\\))\\s*AS\\s+`?(?<alias>\\w+)`?\\s+(?=ON\\s+DUPLICATE\\s+KEY\\s+UPDATE)",
        Pattern.CASE_INSENSITIVE);

    // jsqlparser falls into an infinite loop with these statements so we collapse them
    private String collapseIfStatements(String sql) {
        String ifsRemoved = sql.replaceAll("(?<=\\?)\\s*,\\s*IF\\s*\\([^=]+=\\s*\\?,\\s*\\?\\s*\\)", "");
        while (!ifsRemoved.equals(sql)) {
            sql = ifsRemoved;
            ifsRemoved = sql.replaceAll("(?<=\\?)\\s*,\\s*IF\\s*\\([^=]+=\\s*\\?,\\s*\\?\\s*\\)", "");
        }
        return sql;
    }

    record CleanedSql(String sql, boolean truncated, boolean modified) {

        @Override
            public String toString() {
                return "CleanedSql{" +
                    "sql='" + sql + '\'' +
                    ", truncated=" + truncated +
                    ", modified=" + modified +
                    '}';
            }
        }

}
