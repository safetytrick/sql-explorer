package com.github.safetytrick.sqlexplorer.analysis;

import com.github.safetytrick.sqlexplorer.model.SqlProfile;
import com.github.safetytrick.sqlexplorer.model.TableReference;
import net.sf.jsqlparser.statement.Statement;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Order(2)
@Component
class SchemaAnalyzer implements SqlAnalyzer {
    @Override
    public void analyze(Statement statement, SqlProfile.Builder builder) {
        SqlProfile.Builder.View view = builder.view();
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
