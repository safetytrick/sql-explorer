package com.github.safetytrick.sqlexplorer.evidence.source;

import java.time.Instant;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonIgnore;

public interface SqlSource {
    @JsonIgnore
    Stream<SqlInput> getSql();
    Instant getCreatedOn();
    String getSourceRef();

    default Instant getCapturedAt() {
        return null;
    }

    default java.util.Map<String, String> getCaptureServerContext() {
        return java.util.Map.of();
    }

    default com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext.Origin getCaptureOrigin() {
        return null;
    }
}
