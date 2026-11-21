package com.github.safetytrick.sqlexplorer.evidence;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.analysis.AnalyzerVersion;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;

public final class AnalysisReuse {

    public static final AnalysisReuse NONE = new AnalysisReuse(Map.of());

    private final Map<Key, SqlProfile> byKey;

    private AnalysisReuse(Map<Key, SqlProfile> byKey) {
        this.byKey = byKey;
    }

    public static AnalysisReuse of(Collection<SqlProfile> profiles) {
        return new Builder().addAll(profiles).build();
    }

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

        public Builder addAll(AnalysisReuse other) {
            return addAll(other.byKey.values());
        }

        public AnalysisReuse build() {
            return byKey.isEmpty() ? NONE : new AnalysisReuse(Map.copyOf(byKey));
        }
    }

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
