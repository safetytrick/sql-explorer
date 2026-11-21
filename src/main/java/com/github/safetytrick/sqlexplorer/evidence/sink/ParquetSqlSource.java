package com.github.safetytrick.sqlexplorer.evidence.sink;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.evidence.source.SqlInput;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;

public class ParquetSqlSource implements SqlSource {
    private final String filename;

    public ParquetSqlSource(String filename) {
        this.filename = filename;
    }

    @Override
    public Stream<SqlInput> getSql() {
        return Stream.empty();
    }

    @Override
    public Instant getCreatedOn() {
        try {
            return Files.getLastModifiedTime(Path.of(filename)).toInstant();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Instant getCapturedAt() {
        CaptureContext context = provenance();
        return context == null ? null : context.capturedAt();
    }

    @Override
    public Map<String, String> getCaptureServerContext() {
        CaptureContext context = provenance();
        return context == null || context.server() == null ? Map.of() : context.server();
    }

    private CaptureContext provenance() {
        CaptureContext known = provenance;
        if (known == null && !provenanceRead) {
            known = CaptureArchive.provenanceOf(Path.of(filename));
            provenance = known;
            provenanceRead = true;
        }
        return known;
    }

    private volatile CaptureContext provenance;
    private volatile boolean provenanceRead;

    @Override
    public CaptureContext.Origin getCaptureOrigin() {
        CaptureContext context = provenance();
        return context == null || context.origin() == null
            ? CaptureContext.Origin.PARQUET_COPY : context.origin();
    }

    @Override
    public String getSourceRef() {
        return filename;
    }
}
