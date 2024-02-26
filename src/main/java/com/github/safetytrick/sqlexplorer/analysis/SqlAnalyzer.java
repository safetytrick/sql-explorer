package com.github.safetytrick.sqlexplorer.analysis;

import com.github.safetytrick.sqlexplorer.model.SqlProfile;
import net.sf.jsqlparser.statement.Statement;

@FunctionalInterface
public interface SqlAnalyzer {
    void analyze(Statement statement, SqlProfile.Builder builder);

}
