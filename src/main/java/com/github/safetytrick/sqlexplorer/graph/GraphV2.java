package com.github.safetytrick.sqlexplorer.graph;

import java.util.List;

/** Payload for the canvas graph view. */
public record GraphV2(List<Node> nodes, List<FkEdge> fkEdges, List<CoQueryEdge> coQueryEdges) {

    public record Node(String schema, String table, Integer columnCount,
                       int queryCount, boolean warn, boolean selfJoin) { }

    public record FkEdge(String fromSchema, String fromTable, String toSchema, String toTable,
                         String label, boolean mismatch) { }

    public record CoQueryEdge(String aSchema, String aTable, String bSchema, String bTable,
                              int queries, long executions) { }
}
