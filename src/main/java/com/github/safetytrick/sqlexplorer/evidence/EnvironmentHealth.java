package com.github.safetytrick.sqlexplorer.evidence;

import java.time.Instant;
import java.util.List;

/**
 * What state an environment is in, and why, for the environment selector
 * (sqlx-nbi.8). Never "fine" by omission: a corpus missing a server, or older
 * than the max age, says so.
 *
 * @param state         the one-word answer
 * @param oldestCapture the earliest capture the environment is served from
 * @param maxAgeMinutes how old a cache may be and still count as fresh
 * @param problems      what is wrong, one plain sentence each; empty for
 *                      {@link State#LIVE} and {@link State#CACHED}
 * @param lastPull      the most recent pull attempt; null before any
 * @param configuredSources how many sources it is configured with; with none,
 *                      a pull has nothing to read
 */
public record EnvironmentHealth(State state, Instant oldestCapture, long maxAgeMinutes, List<String> problems,
                                PullAttempt lastPull, int configuredSources) {

    public enum State {
        /** Read live, every source present and within the max age. */
        LIVE,
        /** Served from a fresh cache; no server is connected until it is pulled. */
        CACHED,
        /** A pull is running; what is served meanwhile is whatever was there. */
        PULLING,
        /** Every source is served, but some of it is older than the max age: a pull failed or has not run. */
        STALE,
        /** Some sources have nothing to serve at all: no capture, and they could not be read. */
        PARTIAL,
        /** Nothing to serve. */
        UNAVAILABLE
    }

    /** @param error why it failed; null when it succeeded */
    public record PullAttempt(Instant at, boolean succeeded, String error) { }
}
