package com.github.safetytrick.sqlexplorer.schema;

import java.util.List;

import com.github.safetytrick.sqlexplorer.mcp.McpTool;
import com.github.safetytrick.sqlexplorer.mcp.McpToolProvider;
import org.springframework.stereotype.Component;

/** A table's structure, DDL and foreign keys, as MCP tools. */
@Component
class SchemaMcpTools implements McpToolProvider {

    private final SchemaResource schema;

    SchemaMcpTools(SchemaResource schema) {
        this.schema = schema;
    }

    @Override
    public List<McpTool> tools() {
        return List.of(
            McpTool.named("get_table_structure", "Table metadata: columns, indexes, foreign keys, type (TABLE/VIEW) — "
                    + "and for views, the base tables (underlyingTables) and per-column lineage (viewColumns). "
                    + "stats: per server, information_schema.TABLES figures (rowsEstimate is an estimate, sizes in bytes) "
                    + "with when they were read. null when the table is unknown.")
                .string("schema", null).string("table", null)
                .handler(args -> {
                    Object structure = schema.getTableStructure(args.string("schema"), args.string("table"));
                    return structure != null ? structure : "null — no such table in this environment";
                }),
            McpTool.named("get_table_ddl",
                    "The CREATE TABLE / CREATE VIEW statement for a table, loaded from the live server.")
                .string("schema", null).string("table", null)
                .handler(args -> schema.getCreateStatement(args.string("schema"), args.string("table"))),
            McpTool.named("get_foreign_keys", "Foreign keys in and out of a table, with per-column type-mismatch flags.")
                .string("schema", null).string("table", null)
                .handler(args -> schema.getForeignKeys(args.string("schema"), args.string("table")))
        );
    }
}
