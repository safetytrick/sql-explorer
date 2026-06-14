package com.github.safetytrick.sqlexplorer.analysis;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import net.sf.jsqlparser.statement.Statement;

@FunctionalInterface
public interface SqlAnalyzer {

    /**
     * @param schema the schema of the server the analyzed SQL was captured on,
     *               used to resolve columns to tables. May be {@code null} when
     *               no schema is available (analyzers must tolerate this).
     */
    void analyze(Statement statement, SqlProfile.Builder builder, SqlSchemaRepository schema);

    /** Convenience for callers/tests with no schema context. */
    default void analyze(Statement statement, SqlProfile.Builder builder) {
        analyze(statement, builder, null);
    }
}
