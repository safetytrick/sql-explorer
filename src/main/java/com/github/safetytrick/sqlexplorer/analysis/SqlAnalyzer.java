package com.github.safetytrick.sqlexplorer.analysis;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import net.sf.jsqlparser.statement.Statement;

@FunctionalInterface
public interface SqlAnalyzer {

    void analyze(Statement statement, SqlProfile.Builder builder, SqlSchemaRepository schema);

    default void analyze(Statement statement, SqlProfile.Builder builder) {
        analyze(statement, builder, null);
    }
}
