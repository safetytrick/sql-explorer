package com.github.safetytrick.sqlexplorer.analysis;

import com.github.safetytrick.sqlexplorer.core.AnalyzedSql;
import net.sf.jsqlparser.statement.Statement;

@FunctionalInterface
public interface SqlAnalyzer {
    void analyze(Statement statement, AnalyzedSql.Builder builder);

}
