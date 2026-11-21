package com.github.safetytrick.sqlexplorer.evidence;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CacheFreshnessTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    private static final Duration DAY = Duration.ofHours(24);

    @Test
    void everySourceCapturedWithinTheMaxAgeIsFresh() {
        CacheFreshness freshness = CacheFreshness.evaluate(List.of("primary", "replica"),
            Map.of("primary", NOW.minusSeconds(3600), "replica", NOW.minus(Duration.ofHours(23))),
            List.of(), NOW, DAY);

        assertThat(freshness.fresh()).isTrue();
        assertThat(freshness.reasons()).isEmpty();
        assertThat(freshness.oldestCapture()).isEqualTo(NOW.minus(Duration.ofHours(23)));
    }

    @Test
    void oneSourceOverTheMaxAgeMakesTheEnvironmentStale() {
        CacheFreshness freshness = CacheFreshness.evaluate(List.of("primary", "replica"),
            Map.of("primary", NOW.minusSeconds(60), "replica", NOW.minus(Duration.ofHours(30))),
            List.of(), NOW, DAY);

        assertThat(freshness.fresh()).isFalse();
        assertThat(freshness.reasons()).containsExactly("replica was captured 1d 6h ago, over the 1d 0h limit");
    }

    @Test
    void aSourceWithNoUsableCaptureIsNotFresh() {
        CacheFreshness freshness = CacheFreshness.evaluate(List.of("primary", "replica"),
            Map.of("primary", NOW), List.of(), NOW, DAY);

        assertThat(freshness.fresh()).isFalse();
        assertThat(freshness.reasons()).containsExactly("replica has no usable capture");
    }

    @Test
    void aClusterWithNoSchemaSnapshotIsNotFresh() {
        CacheFreshness freshness = CacheFreshness.evaluate(List.of("primary"), Map.of("primary", NOW),
            List.of("primary"), NOW, DAY);

        assertThat(freshness.fresh()).isFalse();
        assertThat(freshness.reasons()).containsExactly("cluster primary has no schema snapshot");
    }

    @Test
    void anUndatedCaptureAndNoSourcesAreNotFresh() {
        Map<String, Instant> undated = new HashMap<>();
        undated.put("primary", null);

        assertThat(CacheFreshness.evaluate(List.of("primary"), undated, List.of(), NOW, DAY).fresh()).isFalse();
        assertThat(CacheFreshness.evaluate(List.of(), Map.of(), List.of(), NOW, DAY).reasons())
            .containsExactly("no sources are configured");
    }

    @Test
    void aZeroMaxAgeIsNeverFresh() {
        assertThat(CacheFreshness.evaluate(List.of("primary"), Map.of("primary", NOW.minusSeconds(1)), List.of(),
            NOW, Duration.ZERO).fresh()).isFalse();
    }

    @Test
    void agesAreCoarse() {
        assertThat(CacheFreshness.age(Duration.ofSeconds(10))).isEqualTo("10s");
        assertThat(CacheFreshness.age(Duration.ofMinutes(40))).isEqualTo("40m");
        assertThat(CacheFreshness.age(Duration.ofMinutes(312))).isEqualTo("5h 12m");
        assertThat(CacheFreshness.age(Duration.ofHours(76))).isEqualTo("3d 4h");
    }
}
