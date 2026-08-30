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

    /**
     * When the rows were read off the source server, as the file itself
     * records it — not the file's modification time.
     *
     * <p>A Parquet capture is at least two hops from
     * {@code sys.x$statement_analysis}, and the file may have been copied long
     * after it was captured. Its mtime answers "when did this file last
     * change", which is a different question and a misleading answer to this
     * one (sqlx-sy5).
     */
    @Override
    public Instant getCapturedAt() {
        CaptureContext context = provenance();
        return context == null ? null : context.capturedAt();
    }

    /**
     * The source server's capture-time settings, read back out of the footer.
     *
     * <p>These were written by whatever produced the file, at the moment it
     * read the rows. Reading them instead off a live connection would describe
     * a different machine at a different time — so recorded-or-nothing is the
     * only honest rule, and an unannounced file correctly yields empty.
     */
    @Override
    public Map<String, String> getCaptureServerContext() {
        CaptureContext context = provenance();
        return context == null || context.server() == null ? Map.of() : context.server();
    }

    /**
     * Read once. The footer is a seek rather than a scan, but this is asked
     * per coverage signal and the file does not change under a loaded source.
     */
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

    /**
     * What the file records about how ITS rows were obtained, falling back to
     * the one thing that is certain — they came through a Parquet copy.
     * Preferring the footer keeps the chain walkable: a file written from a
     * live read still says so, rather than losing that at the first copy.
     */
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
