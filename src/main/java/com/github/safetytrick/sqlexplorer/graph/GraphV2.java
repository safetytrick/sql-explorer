package com.github.safetytrick.sqlexplorer.graph;

import java.util.List;

/** Payload for the canvas graph view. */
public record GraphV2(List<Node> nodes, List<FkEdge> fkEdges, List<CoQueryEdge> coQueryEdges,
                      List<ViewEdge> viewEdges, List<JoinEdge> joinEdges) {

    /**
     * @param columns the table's key columns, in table order: its primary key,
     *                its foreign keys and every column a drawn join or foreign
     *                key attaches to. What an ERD card lists; the rest are
     *                counted by {@code columnCount}.
     * @param executions  the executions of the queries counted by {@code queryCount}
     * @param queryTimeMs the total time of those queries, in milliseconds. A
     *                    query is counted in full against every table it
     *                    touches, as the table page counts it, so the figures
     *                    of several tables add up to more than the time spent.
     * @param sizeBytes   data plus index bytes, summed over the servers that
     *                    report the table; null when none does, as for a view
     * @param dataBytes   of which the rows ({@code DATA_LENGTH}; for InnoDB the
     *                    clustered primary key); null when no server reports it
     * @param indexBytes  of which the secondary indexes ({@code INDEX_LENGTH})
     */
    public record Node(String schema, String table, Integer columnCount,
                       int queryCount, boolean warn, boolean selfJoin, boolean view,
                       List<KeyColumn> columns, long executions, double queryTimeMs, Long sizeBytes,
                       Long dataBytes, Long indexBytes) { }

    /**
     * One row of an ERD card.
     *
     * @param type       as MySQL names it; null when the table's structure is not known
     * @param unique     the column alone is a unique index, the primary key included
     * @param foreignKey a declared foreign key starts at it
     */
    public record KeyColumn(String name, String type, boolean nullable, boolean primary,
                            boolean unique, boolean foreignKey) { }

    /**
     * One column pair of a relationship, with what the schema says about each
     * end, which is what the relationship's kind is inferred from: a join is
     * rarely backed by a declared key, but a unique end is the "one" side
     * whether or not a constraint says so.
     *
     * <p>{@code aUnique} is true when a unique index is covered by the columns
     * this relationship joins on that side and includes this one. For a join
     * the columns are those of every query joining the two tables, so two
     * queries each joining on half of a composite key read as unique between
     * them. The flags are null when the table's structure is not known.
     *
     * @param declared the side a declared foreign key on exactly this pair
     *                 starts from, {@code "a"} or {@code "b"}; null when none
     */
    public record ColumnPair(String a, String b, Boolean aUnique, Boolean bUnique,
                             Boolean aNullable, Boolean bNullable, String declared) { }

    /**
     * A declared foreign key. {@code joined} says whether any query joins the
     * two tables on its columns; one that is never joined only enforces
     * integrity (sqlx-aoy.3).
     *
     * @param pairs every column pair of the key, {@code a} the referencing side
     */
    public record FkEdge(String fromSchema, String fromTable, String toSchema, String toTable,
                         String label, boolean mismatch, boolean joined, List<ColumnPair> pairs) { }

    public record CoQueryEdge(String aSchema, String aTable, String bSchema, String bTable,
                              int queries, long executions) { }

    /**
     * Two tables joined to each other in at least one query, from analysis
     * rather than the schema: the relationship the application actually uses,
     * whether or not a foreign key declares it (sqlx-aoy.3). Every joined pair
     * is also a co-query pair; not every co-query pair is joined.
     *
     * @param columns the distinct column pairs joined on, as {@code a_col=b_col}
     * @param pairs   the same pairs in the same order, with each end's uniqueness
     */
    public record JoinEdge(String aSchema, String aTable, String bSchema, String bTable,
                           List<String> columns, int queries, long executions, List<ColumnPair> pairs) { }

    /** A view selecting from one of its base tables. */
    public record ViewEdge(String fromSchema, String fromTable, String toSchema, String toTable) { }
}
