package com.github.safetytrick.sqlexplorer.analysis;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import net.sf.jsqlparser.statement.Statement;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Order(4)
@Component
class GeneratedSqlAnalyzer implements SqlAnalyzer {
    @Override
    public void analyze(Statement statement, SqlProfile.Builder builder, SqlSchemaRepository schema) {
        builder.setGeneratorCandidates(GeneratorSignatures.match(statement, builder.getQuery()));
    }
}
