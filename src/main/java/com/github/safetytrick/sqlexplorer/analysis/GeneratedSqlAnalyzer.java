package com.github.safetytrick.sqlexplorer.analysis;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import net.sf.jsqlparser.statement.Statement;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Records which generators' signatures a statement matches (sqlx-n5j.12).
 *
 * <p>Was a single rule — a table aliased {@code this_} meant "hibernate" —
 * written to {@code generatedSource} as though it were an origin. Now every
 * matching rule of every generator in {@link GeneratorSignatures} is stored as
 * a candidate, and whether a candidate is the origin is decided when read,
 * against what a person has confirmed the environment uses.
 * {@code generatedSource} is no longer written.
 */
@Order(4)
@Component
class GeneratedSqlAnalyzer implements SqlAnalyzer {
    @Override
    public void analyze(Statement statement, SqlProfile.Builder builder, SqlSchemaRepository schema) {
        builder.setGeneratorCandidates(GeneratorSignatures.match(statement, builder.getQuery()));
    }
}
