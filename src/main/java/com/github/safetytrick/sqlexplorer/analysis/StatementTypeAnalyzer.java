package com.github.safetytrick.sqlexplorer.analysis;

import java.util.Locale;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import org.springframework.stereotype.Component;

@Component
class StatementTypeAnalyzer implements SqlAnalyzer {

    @Override
    public void analyze(Statement statement, SqlProfile.Builder builder, SqlSchemaRepository schema) {
        String current = builder.hasType() ? builder.getType().toUpperCase(Locale.ROOT) : null;
        String parsed = switch (statement) {
            case Select ignored -> "SELECT";
            case Update ignored -> "UPDATE";
            case Delete ignored -> "DELETE";
            case Insert ignored -> "INSERT".equals(current) || "REPLACE".equals(current) ? current : "INSERT";
            default -> null;
        };
        if (parsed != null) {
            builder.setType(parsed);
        }
    }
}
