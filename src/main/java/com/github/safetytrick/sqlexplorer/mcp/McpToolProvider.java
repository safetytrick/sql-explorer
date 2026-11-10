package com.github.safetytrick.sqlexplorer.mcp;

import java.util.List;

/**
 * Contributes tools to the MCP endpoint. Implemented in each domain package,
 * beside the resources the tools call, so those resources can stay
 * package-private and a tool cannot drift from the endpoint it mirrors.
 */
public interface McpToolProvider {
    List<McpTool> tools();
}
