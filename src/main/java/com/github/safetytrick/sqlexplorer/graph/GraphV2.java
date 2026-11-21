package com.github.safetytrick.sqlexplorer.graph;

import java.util.List;

public record GraphV2(List<Node> nodes, List<FkEdge> fkEdges, List<CoQueryEdge> coQueryEdges,
                      List<ViewEdge> viewEdges, List<JoinEdge> joinEdges) {

    public record Node(String schema, String table, Integer columnCount,
                       int queryCount, boolean warn, boolean selfJoin, boolean view,
                       List<KeyColumn> columns, long executions, double queryTimeMs, Long sizeBytes,
                       Long dataBytes, Long indexBytes) { }

    public record KeyColumn(String name, String type, boolean nullable, boolean primary,
                            boolean unique, boolean foreignKey) { }

    public record ColumnPair(String a, String b, Boolean aUnique, Boolean bUnique,
                             Boolean aNullable, Boolean bNullable, String declared) { }

    public record FkEdge(String fromSchema, String fromTable, String toSchema, String toTable,
                         String label, boolean mismatch, boolean joined, List<ColumnPair> pairs) { }

    public record CoQueryEdge(String aSchema, String aTable, String bSchema, String bTable,
                              int queries, long executions) { }

    public record JoinEdge(String aSchema, String aTable, String bSchema, String bTable,
                           List<String> columns, int queries, long executions, List<ColumnPair> pairs) { }

    public record ViewEdge(String fromSchema, String fromTable, String toSchema, String toTable) { }
}
