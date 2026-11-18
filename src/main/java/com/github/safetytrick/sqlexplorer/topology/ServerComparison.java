package com.github.safetytrick.sqlexplorer.topology;

import java.util.List;
import java.util.Map;

/**
 * The queries a workbench search matches, compared across one cluster's
 * servers, ranked by how differently they run there (sqlx-a9h.2).
 *
 * <p>Most queries run the same everywhere, so a plain query-by-server matrix
 * is thousands of near-identical rows. Each row is therefore checked for the
 * differences worth acting on ({@link Flag}), and the rows that have none are
 * left out unless asked for. The search decides which queries are compared;
 * this decides which of them are interesting.
 *
 * <p>Every per-day figure divides a server's own total by its own window:
 * each server's counters run from its own last reset, so totals across
 * servers are not comparable.
 */
public final class ServerComparison {

    private ServerComparison() { }

    /** A difference between servers worth looking at. Each is defined in the UI glossary. */
    public enum Flag {
        /** A read that runs on the primary of a cluster that has replicas to take it. */
        ON_PRIMARY,
        /** A read whose executions are spread far from evenly over the cluster's replicas. */
        SKEWED,
        /** A read seen on only one of the cluster's non-primary servers, and not on the primary. */
        SINGLE_SERVER,
        /** Rows examined per execution differ by an order of magnitude between servers: a different plan. */
        PLAN_DIFFERS,
        /** Rows examined match, but time per execution differs several-fold. */
        LATENCY_DIFFERS,
        /** Writes, or rows changed, on a server declared a replica. */
        REPLICA_WRITES
    }

    /** One row per query, or per table with its queries' figures added. */
    public enum By { QUERY, TABLE }

    /** {@code DIFFERENCE}: the time per day its flags are about, largest first; {@code TIME}: seconds per day. */
    public enum Sort { DIFFERENCE, TIME }

    /**
     * A server of the cluster, in the order its cells are given.
     *
     * @param read       whether anything was loaded from it; one that was not
     *                   has no cells and takes no part in the flags
     * @param windowDays the span its counters cover; null when unknown
     */
    public record Column(String name, List<String> roles, boolean read, Double windowDays) { }

    /**
     * What one query (or table) recorded on one server.
     *
     * @param secondsPerDay            seconds over the server's own window, floored at a day
     * @param rowsExaminedPerExecution rows examined over executions
     * @param msPerExecution           total time over executions, in milliseconds
     */
    public record Cell(long executions, double seconds, double secondsPerDay, double executionsPerDay,
                       double rowsExaminedPerExecution, double msPerExecution, long rowsAffected) { }

    /**
     * @param key        the query's digest (open it at {@code /analyzed-sql/{key}}), or a
     *                   table's {@code schema.table}
     * @param text       the query's text, cut short, or the table's name
     * @param kind       READ, WRITE, OTHER or UNKNOWN; a table's is null
     * @param difference the time per day the row's flags are about: the largest of
     *                   their costs. A table's is the sum of its queries'
     * @param cells      one per {@link Report#servers()}, null where it did not run
     * @param queries    for a table, how many compared queries touch it; 1 for a query
     */
    public record Row(String key, String text, String kind, List<Flag> flags, double difference,
                      double secondsPerDay, List<Cell> cells, int queries) { }

    /**
     * @param clusters   every cluster of the environment, for choosing another
     * @param matched    queries the search matched
     * @param compared   of those, rows with figures on any server of the cluster
     * @param flagCounts compared rows carrying each flag, before the flag filter
     * @param unflagged  compared rows carrying none
     * @param total      rows passing the filter, of which {@code rows} is the first page
     */
    public record Report(String environment, List<String> clusters, String cluster, List<Column> servers, By by,
                         int matched, int compared, Map<Flag, Integer> flagCounts, int unflagged, int total,
                         List<Row> rows, String basis) { }
}
