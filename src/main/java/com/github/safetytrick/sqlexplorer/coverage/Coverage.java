package com.github.safetytrick.sqlexplorer.coverage;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

public final class Coverage {

    private Coverage() {
    }

    public enum Verdict {
        OK,
        IMPAIRED,
        UNKNOWN
    }

    public enum Signal {
        DIGEST_CAPACITY,
        DIGEST_TRUNCATION,
        OBSERVATION_WINDOW,
        PROVENANCE,
        SAMPLING_GAPS,
        ARCHIVED_HISTORY,
        UNREACHABLE,
        PENDING_ANALYSIS
    }

    public record Finding(Signal signal, Verdict verdict, String measurement, String basis, String explanation) {
    }

    public record ObservedWindow(LocalDateTime from, LocalDateTime to, long days) {
    }

    public record SourceCoverage(String sourceRef, String origin, Instant capturedAt, int profiles,
                                 ObservedWindow window, List<Finding> findings) {
    }

    public record Report(String environment, boolean loaded, Instant loadedOn, String originTable,
                         List<SourceCoverage> sources,
                         List<com.github.safetytrick.sqlexplorer.environment.Environment.ClusterSchemaStatus> clusters,
                         List<com.github.safetytrick.sqlexplorer.environment.Environment.TableDrift> drift) {
    }
}
