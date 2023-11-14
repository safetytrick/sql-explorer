package com.github.safetytrick.sqlexplorer.analysis;

import com.github.safetytrick.sqlexplorer.core.AnalyzedSql;
import com.github.safetytrick.sqlexplorer.core.TableReference;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import net.sf.jsqlparser.statement.Statement;

@Order(2)
@Component
public class SchemaAnalyzer implements SqlAnalyzer {
    @Override
    public void analyze(Statement statement, AnalyzedSql.Builder builder) {
        AnalyzedSql.Builder.View view = builder.view();
        if (!view.getSchemas().isEmpty()) {
            boolean isMultiSchema = false;
            String referencedSchema = null;
            for (TableReference table : view.getTables()) {
                String tableRefSchema = table.getSchema() == null ? view.getSchema() : table.getNormalizedSchema();

                if (referencedSchema == null) {
                    referencedSchema = tableRefSchema;
                } else if (!referencedSchema.equalsIgnoreCase(tableRefSchema)) {
                    isMultiSchema = true;
                    break;
                }
            }

            if (isMultiSchema) {
                builder.isMultiSchema(true);
                return;
            }

            if (view.getSchemas().size() == 1) {
                builder.schema(view.getSchemas().get(0));
            } else {
                builder.isMultiSchema(true);
            }
        }
    }
}
