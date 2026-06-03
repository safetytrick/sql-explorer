package com.github.safetytrick.sqlexplorer.analysis;

import java.util.ArrayList;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import net.sf.jsqlparser.statement.Statement;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Order(2)
@Component
class SchemaAnalyzer implements SqlAnalyzer {
    @Override
    public void analyze(Statement statement, SqlProfile.Builder builder) {
        if (builder.getTables() == null) {
            builder.setTables(new ArrayList<>());
        }
        if (builder.getSchemas() == null) {
            builder.setSchemas(new ArrayList<>());
        }

        for (TableReference table : builder.getTables()) {
            builder.addSchema(table.getNormalizedSchemaName());
        }

        if (builder.getSchemas() != null && !builder.getSchemas().isEmpty()) {
            boolean isMultiSchema = false;
            String referencedSchema = null;
            for (TableReference table : builder.getTables()) {
                String tableRefSchema = table.getSchemaName() == null ? builder.getSchemaName() : table.getNormalizedSchemaName();

                if (referencedSchema == null) {
                    referencedSchema = tableRefSchema;
                } else if (!referencedSchema.equalsIgnoreCase(tableRefSchema)) {
                    isMultiSchema = true;
                    break;
                }
            }

            if (isMultiSchema) {
                builder.setIsMultiSchema(true);
                return;
            }

            if (builder.getSchemas().size() == 1) {
                builder.setSchemaName(builder.getSchemas().getFirst());
            } else {
                builder.setIsMultiSchema(true);
            }
        }
    }
}
