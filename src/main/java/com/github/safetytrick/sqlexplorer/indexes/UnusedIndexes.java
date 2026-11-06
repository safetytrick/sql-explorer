package com.github.safetytrick.sqlexplorer.indexes;

import java.time.Instant;
import java.util.List;

import com.github.safetytrick.sqlexplorer.coverage.Coverage;

/**
 * Indexes nothing in an environment has read.
 *
 * <p>THE MOST DANGEROUS REPORT IN THE PRODUCT, and it is worth saying so in the
 * type. Every other report leads a reader to a query; this one leads to
 * {@code DROP INDEX}. Everything below is shaped by that: what it takes to be
 * reported, and what has to be said alongside.
 */
public final class UnusedIndexes {

    private UnusedIndexes() {
    }

    /** One index, on whichever connections have it. */
    public record Index(String schema, String table, String name) {
    }

    /**
     * What one connection said.
     *
     * @param connection      the server's configured name
     * @param verdict         OK when read and observing long enough, IMPAIRED
     *                        when the window is too short to support a claim of
     *                        disuse, UNKNOWN when it could not be read at all
     * @param problem         why it could not be read, or null
     * @param note            how it was read when not the usual way (no sys
     *                        schema, sqlx-jip), or null
     * @param upSeconds       how long the server has been up, or null
     * @param unusedHere      how many unused indexes it reported
     * @param readHere        how many indexes it has seen reads on
     */
    public record Connection(String connection, Coverage.Verdict verdict, String problem, String note,
                             Long upSeconds, int unusedHere, int readHere) {
    }

    /**
     * @param environment which environment was asked about
     * @param connections one entry per configured server, never merged
     * @param complete    whether every connection answered — when false the
     *                    list below is not a claim about the environment, only
     *                    about the connections that spoke
     * @param unused      indexes no connection has read
     * @param basis       the sentence a reader needs before acting on this
     * @param readAt      when the connections were read; the page shows the
     *                    last report until asked to read again, and it is
     *                    kept on disk across restarts (sqlx-1bo.2)
     * @param readMillis  how long reading every connection took, which is
     *                    what reading again will cost
     * @param minimumDays the uptime a connection needed for its verdict to be
     *                    OK when this was read; the setting can change since
     */
    public record Report(String environment, List<Connection> connections, boolean complete,
                         List<Index> unused, String basis, Instant readAt, long readMillis, long minimumDays) {
    }
}
