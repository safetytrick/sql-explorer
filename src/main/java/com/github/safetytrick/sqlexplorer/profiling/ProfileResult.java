package com.github.safetytrick.sqlexplorer.profiling;

import java.util.List;

/**
 * What a profile run found (sqlx-n5j.3): per server, and combined across
 * every server it read. Aggregates first; the only things from the table
 * itself are row keys and the few characters a signature matched.
 *
 * @param servers    one entry per cluster (or the one probed server)
 * @param columns    every server's figures added together
 * @param confidence {@code confirmed} when every server read every row, else {@code sampled}
 */
public record ProfileResult(List<ServerResult> servers, List<ColumnResult> columns, String confidence) {

    /**
     * One server's run.
     *
     * @param status      {@code done}, {@code failed}, {@code cancelled} or {@code skipped}
     * @param complete    every row of the table was read, so the figures are a census, not a sample
     * @param statements  the statements issued, as a person reads them; the first ones when there are many
     */
    public record ServerResult(String cluster, String server, Long rowsEstimate, String status, String reason,
                               boolean complete, String coverage, List<WindowResult> windows, long rowsRead,
                               int statementCount, List<String> statements, List<ColumnResult> columns) { }

    /** One window: where it started and ended, how many rows, how long, and its error if it failed. */
    public record WindowResult(String label, String from, String to, long rows, long millis, String error) { }

    /**
     * One column's figures.
     *
     * @param distinctAtLeast the most distinct values any one window held: a floor, not an estimate
     * @param verdict         what the figures mean for a utf8mb4 conversion, in a sentence
     */
    public record ColumnResult(String column, String type, String characterSet, String collation, long rows,
                               long nulls, long nonAscii, Long maxBytes, long distinctAtLeast,
                               List<SignatureResult> signatures, String verdict) { }

    /**
     * Rows matching one signature.
     *
     * @param ids        the keys of the first matching rows, per server
     * @param characters what it matched, and what each most likely was
     */
    public record SignatureResult(Signature signature, String label, String explanation, boolean heuristic,
                                  boolean informational, long rows, List<ServerIds> ids,
                                  List<CharacterCount> characters) { }

    /** Row keys on one server: {@code id} values, or {@code a/b} for a composite key. */
    public record ServerIds(String server, List<String> ids) { }

    /**
     * One matched sequence.
     *
     * @param matched    the characters as stored, e.g. {@code Ã©}
     * @param codePoints the same as U+ code points, since several are invisible or look alike
     * @param intended   what it most likely was, e.g. é; null when that cannot be told
     * @param rows       rows in which it is the first match
     */
    public record CharacterCount(String matched, String codePoints, String intended, long rows) { }
}
