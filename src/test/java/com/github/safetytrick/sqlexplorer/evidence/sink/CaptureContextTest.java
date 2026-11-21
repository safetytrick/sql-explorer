package com.github.safetytrick.sqlexplorer.evidence.sink;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CaptureContextTest {

    private static CaptureContext context(Map<String, String> server) {
        return new CaptureContext(
            CaptureContext.Origin.PARQUET_COPY, "primary",
            Instant.parse("2026-08-29T14:03:35Z"), 4202,
            LocalDateTime.parse("2026-05-04T15:49:26"),
            LocalDateTime.parse("2026-08-29T14:03:35"),
            server);
    }

    @Test
    void namesTheOriginTableEveryCorpusComesFrom() {
        assertThat(context(Map.of()).asMetadata())
            .containsEntry("sqlexplorer.originTable", "sys.x$statement_analysis")
            .containsEntry("sqlexplorer.origin", "PARQUET_COPY");
    }

    @Test
    void carriesTheSampleIdentityGapDetectionNeeds() {
        assertThat(context(Map.of()).asMetadata())
            .containsEntry("sqlexplorer.sourceRef", "primary")
            .containsEntry("sqlexplorer.capturedAt", "2026-08-29T14:03:35Z");
    }

    @Test
    void statesTheWindowItObserved() {
        assertThat(context(Map.of()).asMetadata())
            .containsEntry("sqlexplorer.observedFrom", "2026-05-04T15:49:26")
            .containsEntry("sqlexplorer.observedTo", "2026-08-29T14:03:35")
            .containsEntry("sqlexplorer.rowCount", "4202");
    }

    @Test
    void namespacesServerVariablesApart() {
        Map<String, String> server = new LinkedHashMap<>();
        server.put("performance_schema_digests_size", "10000");
        server.put("performance_schema_max_digest_length", "4096");

        assertThat(context(server).asMetadata())
            .containsEntry("mysql.performance_schema_digests_size", "10000")
            .containsEntry("mysql.performance_schema_max_digest_length", "4096");
    }

    @Test
    void omitsWhatItDoesNotKnowRatherThanWritingNull() {
        CaptureContext unknown = new CaptureContext(null, null, null, 0, null, null, null);

        assertThat(unknown.asMetadata().keySet())
            .containsExactly("sqlexplorer.originTable", "sqlexplorer.rowCount");
        assertThat(unknown.asMetadata().values()).doesNotContain("null");
    }

    @Test
    void anEmptyServerMapAddsNothing() {
        assertThat(context(Map.of()).asMetadata().keySet()).noneMatch(key -> key.startsWith("mysql."));
    }
}
