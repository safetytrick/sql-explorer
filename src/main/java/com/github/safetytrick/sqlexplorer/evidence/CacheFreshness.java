package com.github.safetytrick.sqlexplorer.evidence;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CacheFreshness(boolean fresh, Instant oldestCapture, List<String> reasons) {

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
