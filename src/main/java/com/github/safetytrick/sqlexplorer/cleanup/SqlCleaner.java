package com.github.safetytrick.sqlexplorer.cleanup;

import org.springframework.stereotype.Component;

@Component
public class SqlCleaner {

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
            .replace("BINARY `", "`")
            .replace("BINARY (", "CAST (")
            .replace(" RLIKE ", " LIKE ")
            .replace("SEPARATOR ?", "SEPARATOR '|'")
            .replace("AS CHARACTER (?)", "AS CHARACTER ()")
            .replaceAll("= TRUE(\\s|$)", "= 1 ")
            .replaceAll("\\?(\\s+\\?)+", "?")
            .replaceAll("\\s`\\s+", " `");

        sql = collapseIfStatements(sql);

        String beforeTruncated = sql;

        sql = sql.replaceFirst("[.,]\\s*$", "")
            .replaceFirst("=\\s*$", "= 1")
            .replaceFirst("\\s(AND|OR|ON|WHERE)\\s*\\(?\\s*$", "");

        return new CleanedSql(sql, !beforeTruncated.equals(sql), !sql.equals(beforeMundane));
    }


    // jsqlparser falls into an infinite loop with these statements so we collapse them
    private String collapseIfStatements(String sql) {
        String ifsRemoved = sql.replaceAll("(?<=\\?)\\s*,\\s*IF\\s*\\([^=]+=\\s*\\?,\\s*\\?\\s*\\)", "");
        while (!ifsRemoved.equals(sql)) {
            sql = ifsRemoved;
            ifsRemoved = sql.replaceAll("(?<=\\?)\\s*,\\s*IF\\s*\\([^=]+=\\s*\\?,\\s*\\?\\s*\\)", "");
        }
        return sql;
    }

    public record CleanedSql(String sql, boolean truncated, boolean modified) {

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
