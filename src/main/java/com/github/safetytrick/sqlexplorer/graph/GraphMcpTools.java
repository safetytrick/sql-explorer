package com.github.safetytrick.sqlexplorer.graph;

import java.util.List;

import com.github.safetytrick.sqlexplorer.mcp.McpTool;
import com.github.safetytrick.sqlexplorer.mcp.McpToolProvider;
import org.springframework.stereotype.Component;

@Component
class GraphMcpTools implements McpToolProvider {

    private final GraphV2Resource graph;

    GraphMcpTools(GraphV2Resource graph) {
        this.graph = graph;
    }

    @Override
    public List<McpTool> tools() {
        return List.of(
            McpTool.named("get_graph", "One schema's table-relationship graph: nodes with query counts, FK edges "
                    + "(with type-mismatch flags), co-query edges, and view→base-table edges.")
                .string("schema", null)
                .handler(args -> graph.getSchemaGraph(args.string("schema")))
        );
    }
}
