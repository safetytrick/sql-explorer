package com.github.safetytrick.sqlexplorer.analysis;

import com.github.safetytrick.sqlexplorer.analysis.parser.QueryNormalizer;

/**
 * Which version of sql-explorer's own analysis produced a profile.
 *
 * <p>A digest's analysis is reused on later loads without parsing it again —
 * forever, even across schema changes (docs/plans/cluster-schema-snapshots.md,
 * sqlx-sqy.5). That is only sound while the analysis code itself is unchanged,
 * so a profile records this, and reuse requires it to match. A profile from an
 * older version counts as never seen and is analysed afresh.
 *
 * <p><b>Bump {@link #ANALYZERS} whenever a change to parsing, the analyzers,
 * table extraction or the profile's derived fields would make the same
 * statement analyse differently.</b> Forgetting is silent: old analysis keeps
 * being served. The normalizer's version is folded in automatically, because
 * a reused profile carries its normalized text and digest, and those must come
 * from the scheme running now.
 */
public final class AnalyzerVersion {

    /**
     * The analyzers' own version.
     *
     * <ul>
     *   <li>1 — the first recorded version.</li>
     *   <li>2 — a correlated subquery's references to the enclosing query
     *       resolve to that query's tables, and joins inside WHERE-clause
     *       subqueries are recorded (sqlx-lww).</li>
     *   <li>3 — a join to a derived table resolves its columns whatever their
     *       case or quoting, and never to a table named after the column
     *       (sqlx-9xg).</li>
     *   <li>4 — select-list and GROUP BY columns of a derived table resolve
     *       to its source table rather than dual, and a UNION derived table
     *       resolves through its first branch (sqlx-03r).</li>
     *   <li>5 — index hints are recorded (sqlx-yj0).</li>
     *   <li>6 — ORDER BY columns are recorded (sqlx-n5j.8); criteria
     *       columns carry how they are compared, LIKE and its leading
     *       wildcard included (sqlx-n5j.7); and generator signatures: every
     *       matching rule of every generator is recorded as a candidate, and
     *       generatedSource is no longer written (sqlx-n5j.12).</li>
     *   <li>7 — a WITH clause opening an UPDATE or DELETE types the profile
     *       UPDATE or DELETE rather than WITH (sqlx-a9h).</li>
     *   <li>8 — a profile's type is its operation: read past any CTEs or
     *       opening parenthesis, from the parse when there is one, and
     *       UNKNOWN when the text does not reach it. WITH and ( are no
     *       longer types (sqlx-a9h).</li>
     * </ul>
     */
    public static final int ANALYZERS = 8;

    /** What a profile records, and what reuse compares: {@code a<ANALYZERS>.n<QueryNormalizer.VERSION>}. */
    public static final String CURRENT = "a" + ANALYZERS + ".n" + QueryNormalizer.VERSION;

    private AnalyzerVersion() {
    }
}
