package com.github.safetytrick.sqlexplorer.evidence;

import java.util.concurrent.atomic.AtomicInteger;

public record SqlImportStats(
    int count,
    int merged,
    int notMerged,
    int analysisFailed,
    int analysisSkipped,
    int truncated,
    int dml
) {

    public SqlImportStats mergedWith(SqlImportStats other) {
        return new SqlImportStats(count + other.count,
            merged + other.merged,
            notMerged + other.notMerged,
            analysisFailed + other.analysisFailed,
            analysisSkipped + other.analysisSkipped,
            truncated + other.truncated,
            dml + other.dml);
    }

    @Override
    public String toString() {
        return "SqlImportStats{" +
            "count=" + count +
            ", merged=" + merged +
            ", notMerged=" + notMerged +
            ", analysisFailed=" + analysisFailed +
            ", analysisSkipped=" + analysisSkipped +
            ", truncated=" + truncated +
            ", dml=" + dml +
            '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final AtomicInteger merged = new AtomicInteger();
        private final AtomicInteger notMerged = new AtomicInteger();
        private final AtomicInteger analysisFailed = new AtomicInteger();
        private final AtomicInteger analysisSkipped = new AtomicInteger();
        private final AtomicInteger truncated = new AtomicInteger();
        private final AtomicInteger dml = new AtomicInteger();
        private final AtomicInteger count = new AtomicInteger();

        public Builder merged() {
            merged.incrementAndGet();
            return this;
        }

        public Builder notMerged() {
            notMerged.incrementAndGet();
            return this;
        }

        public Builder analysisFailed() {
            this.analysisFailed.incrementAndGet();
            return this;
        }

        public Builder truncated() {
            this.truncated.incrementAndGet();
            return this;
        }

        public Builder dml() {
            this.dml.incrementAndGet();
            return this;
        }

        public Builder count() {
            this.count.incrementAndGet();
            return this;
        }

        public SqlImportStats build() {
            return new SqlImportStats(count.get(),
                merged.get(),
                notMerged.get(),
                analysisFailed.get(),
                analysisSkipped.get(),
                truncated.get(),
                dml.get());
        }

        public Builder analysisSkipped() {
            this.analysisSkipped.incrementAndGet();
            return this;
        }
    }
}
