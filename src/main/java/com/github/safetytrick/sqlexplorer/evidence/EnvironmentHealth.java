package com.github.safetytrick.sqlexplorer.evidence;

import java.time.Instant;
import java.util.List;

public record EnvironmentHealth(State state, Instant oldestCapture, long maxAgeMinutes, List<String> problems,
                                PullAttempt lastPull, int configuredSources) {

    public enum State {
        LIVE,
        CACHED,
        PULLING,
        STALE,
        PARTIAL,
        UNAVAILABLE
    }

    public record PullAttempt(Instant at, boolean succeeded, String error) { }
}
