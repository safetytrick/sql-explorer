package com.github.safetytrick.sqlexplorer.analysis.parser;

import java.util.List;

import com.github.safetytrick.sqlexplorer.mcp.McpTool;
import com.github.safetytrick.sqlexplorer.mcp.McpToolProvider;
import org.springframework.stereotype.Component;

@Component
class SqlUtilityMcpTools implements McpToolProvider {

    private final QueryCleanupResource cleanup;

    SqlUtilityMcpTools(QueryCleanupResource cleanup) {
        this.cleanup = cleanup;
    }

    @Override
    public List<McpTool> tools() {
        return List.of(
            McpTool.named("clean_sql", "Clean performance-schema digest text into parseable SQL "
                    + "(placeholder runs collapsed, truncation repaired).")
                .string("sql", null)
                .environmentFree()
                .handler(args -> cleanup.cleanQuery(args.string("sql"))),
            McpTool.named("normalize_sql", "Normalize a query the way the digest pipeline does: aliases stripped, "
                    + "repeated blocks collapsed (with located repeats), formatted.")
                .string("sql", null)
                .optionalBoolean("clean", "run the digest-text cleaner first (default true)")
                .environmentFree()
                .handler(args -> cleanup.normalizeQuery(args.string("sql"), args.flag("clean", true)))
        );
    }
}
