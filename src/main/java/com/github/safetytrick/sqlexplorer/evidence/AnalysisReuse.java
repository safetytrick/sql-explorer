package com.github.safetytrick.sqlexplorer.evidence;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.analysis.AnalyzerVersion;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;

/**
 * Earlier analysis of each statement, so a load can reuse it instead of
 * parsing again (docs/plans/cluster-schema-snapshots.md, sqlx-sqy.5).
 *
 * <p>Keyed by MySQL's digest <em>and</em> the row's own default schema. The
 * digest is computed from the normalized text alone, so the same unqualified
 * statement under two default schemas shares one — but resolves to different
 * tables. Only profiles from the {@link AnalyzerVersion#CURRENT} analysis are
 * admitted; anything older counts as never seen.
 *
 * <p>Reuse is forever, by decision: a schema change does not invalidate an
 * analysis. A new analyzer version does.
 */
public final class AnalysisReuse {

    /** No earlier analysis: every row is analysed (or waits for a current schema). */
    public static final AnalysisReuse NONE = new AnalysisReuse(Map.of());

    private final Map<Key, SqlProfile> byKey;

    private AnalysisReuse(Map<Key, SqlProfile> byKey) {
        this.byKey = byKey;
    }

    /** From earlier profiles — a capture's raw rows, merged members included. The latest-seen per key wins. */
    public static AnalysisReuse of(Collection<SqlProfile> profiles) {
        return new Builder().addAll(profiles).build();
    }

    /**
     * Accumulates one capture at a time, keeping one profile per key, so an
     * environment's captures need not all be in memory at once — replicas
     * share most of their digests, so the index is far smaller than their sum.
     */
    public static final class Builder {
        private final Map<Key, SqlProfile> byKey = new HashMap<>();

        public Builder addAll(Collection<SqlProfile> profiles) {
            for (SqlProfile profile : profiles) {
                if (!AnalyzerVersion.CURRENT.equals(profile.getAnalyzerVersion())
                    || profile.getRuntimeMetrics() == null || profile.getRuntimeMetrics().getDigest() == null) {
                    continue;
                }
                byKey.merge(new Key(profile.getRuntimeMetrics().getDigest(), profile.getSessionSchema()), profile,
                    (kept, candidate) -> lastSeen(candidate).isAfter(lastSeen(kept)) ? candidate : kept);
            }
            return this;
        }

        /** Another index's entries, the latest-seen per key still winning. */
        public Builder addAll(AnalysisReuse other) {
            return addAll(other.byKey.values());
        }

        public AnalysisReuse build() {
            return byKey.isEmpty() ? NONE : new AnalysisReuse(Map.copyOf(byKey));
        }
    }

    /** The earlier analysis of this digest under this default schema, or null if it was never analysed. */
    public SqlProfile find(String digest, String sessionSchema) {
        return digest == null ? null : byKey.get(new Key(digest, sessionSchema));
    }

    public int size() {
        return byKey.size();
    }

    private static java.time.LocalDateTime lastSeen(SqlProfile profile) {
        java.time.LocalDateTime seen = profile.getRuntimeMetrics().getLastSeen();
        return seen == null ? java.time.LocalDateTime.MIN : seen;
    }

    private record Key(String digest, String sessionSchema) { }
}
