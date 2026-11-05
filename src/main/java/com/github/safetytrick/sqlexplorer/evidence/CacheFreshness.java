package com.github.safetytrick.sqlexplorer.evidence;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Whether an environment's cache is fresh enough to serve with no server
 * connected (sqlx-nbi.1).
 *
 * <p>FRESH ONLY WHEN WHOLE. Every configured source needs a usable capture
 * and every cluster a schema snapshot, and the oldest capture must be within
 * the max age. One stale or missing source makes the whole environment stale,
 * and the whole environment is pulled: serving it from cache would mean
 * serving it without that server's queries, with nothing to say so.
 *
 * <p>The schema snapshot's own age is deliberately not compared. A snapshot
 * is rewritten only when the schema changes, so an old one is usually a
 * confirmed one; what ages is the query data.
 *
 * @param oldestCapture the earliest capture among the sources, or null when
 *                      none was served
 * @param reasons       why it is not fresh; empty when it is
 */
public record CacheFreshness(boolean fresh, Instant oldestCapture, List<String> reasons) {

    /**
     * @param configured          the environment's configured source refs
     * @param served              what each served source was captured at, from
     *                            {@link SqlRepositoryManager#servedCaptures}
     * @param clustersWithoutSnapshot clusters whose schema could only be read live
     */
    public static CacheFreshness evaluate(List<String> configured, Map<String, Instant> served,
                                          List<String> clustersWithoutSnapshot, Instant now, Duration maxAge) {
        List<String> reasons = new ArrayList<>();
        if (configured.isEmpty()) {
            reasons.add("no sources are configured");
        }
        Instant oldest = served.values().stream().filter(Objects::nonNull).min(Instant::compareTo).orElse(null);
        for (String source : configured) {
            if (!served.containsKey(source)) {
                reasons.add(source + " has no usable capture");
            } else if (served.get(source) == null) {
                reasons.add(source + "'s capture does not say when it was taken");
            } else if (served.get(source).isBefore(now.minus(maxAge))) {
                reasons.add(source + " was captured " + age(Duration.between(served.get(source), now))
                    + " ago, over the " + age(maxAge) + " limit");
            }
        }
        clustersWithoutSnapshot.forEach(cluster -> reasons.add("cluster " + cluster + " has no schema snapshot"));
        return new CacheFreshness(reasons.isEmpty(), oldest, List.copyOf(reasons));
    }

    /** "3d 4h", "5h 12m", "40m", "10s": coarse, for a log line or a status. */
    static String age(Duration duration) {
        if (duration.toMinutes() < 1) {
            return Math.max(0, duration.toSeconds()) + "s";
        }
        long minutes = duration.toMinutes();
        if (minutes >= 60 * 24) {
            return (minutes / (60 * 24)) + "d " + (minutes % (60 * 24)) / 60 + "h";
        }
        if (minutes >= 60) {
            return (minutes / 60) + "h " + (minutes % 60) + "m";
        }
        return minutes + "m";
    }
}
