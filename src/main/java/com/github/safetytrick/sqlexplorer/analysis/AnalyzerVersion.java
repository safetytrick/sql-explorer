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

    /** The analyzers' own version. */
    public static final int ANALYZERS = 1;

    /** What a profile records, and what reuse compares: {@code a<ANALYZERS>.n<QueryNormalizer.VERSION>}. */
    public static final String CURRENT = "a" + ANALYZERS + ".n" + QueryNormalizer.VERSION;

    private AnalyzerVersion() {
    }
}
