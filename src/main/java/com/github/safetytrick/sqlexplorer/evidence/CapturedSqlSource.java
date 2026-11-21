package com.github.safetytrick.sqlexplorer.evidence;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlInput;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;

public record CapturedSqlSource(Path file, CaptureContext capture) implements SqlSource {

    @Override
    public Stream<SqlInput> getSql() {
        return Stream.empty();
    }

    @Override
    public Instant getCreatedOn() {
        return capture == null ? null : capture.capturedAt();
    }

    @Override
    public Instant getCapturedAt() {
        return capture == null ? null : capture.capturedAt();
    }

    @Override
    public Map<String, String> getCaptureServerContext() {
        return capture == null || capture.server() == null ? Map.of() : capture.server();
    }

    @Override
    public CaptureContext.Origin getCaptureOrigin() {
        return capture == null ? null : capture.origin();
    }

    @Override
    public String getSourceRef() {
        return capture == null || capture.sourceRef() == null
            ? String.valueOf(file.getFileName())
            : capture.sourceRef();
    }

    @Override
    public String toString() {
        return getSourceRef() + "@" + getCapturedAt() + " (from " + file.getFileName() + ")";
    }
}
