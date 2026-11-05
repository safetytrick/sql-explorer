package com.github.safetytrick.sqlexplorer.graph;

import java.util.List;

/** Payload for the canvas graph view. */
public record GraphV2(List<Node> nodes, List<FkEdge> fkEdges, List<CoQueryEdge> coQueryEdges,
                      List<ViewEdge> viewEdges, List<JoinEdge> joinEdges) {

    public record Node(String schema, String table, Integer columnCount,
                       int queryCount, boolean warn, boolean selfJoin, boolean view) { }

    /**
     * A declared foreign key. {@code joined} says whether any query joins the
     * two tables on its columns; one that is never joined only enforces
     * integrity (sqlx-aoy.3).
     */
    public record FkEdge(String fromSchema, String fromTable, String toSchema, String toTable,
                         String label, boolean mismatch, boolean joined) { }

    public record CoQueryEdge(String aSchema, String aTable, String bSchema, String bTable,
                              int queries, long executions) { }

    /**
     * Two tables joined to each other in at least one query, from analysis
     * rather than the schema: the relationship the application actually uses,
     * whether or not a foreign key declares it (sqlx-aoy.3). Every joined pair
     * is also a co-query pair; not every co-query pair is joined.
     *
     * @param columns the distinct column pairs joined on, as {@code a_col=b_col}
     */
    public record JoinEdge(String aSchema, String aTable, String bSchema, String bTable,
                           List<String> columns, int queries, long executions) { }

    /** A view selecting from one of its base tables. */
    public record ViewEdge(String fromSchema, String fromTable, String toSchema, String toTable) { }
}
