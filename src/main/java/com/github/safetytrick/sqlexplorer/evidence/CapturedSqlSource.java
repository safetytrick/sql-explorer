package com.github.safetytrick.sqlexplorer.evidence;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlInput;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;

/**
 * A source rebuilt from one capture file, describing itself from that file's
 * footer rather than from a live connection.
 *
 * <p>This is what makes a rehydrated environment answerable to {@code
 * /coverage} on the same terms as a freshly imported one. The footer records
 * what the server was configured like when the rows were read, so the digest
 * limits and the observed window describe the capture rather than whatever
 * connection happens to be open now — which is the distinction sqlx-sy5 exists
 * to keep (sqlx-4ww).
 *
 * <p>{@link #getSql()} is empty on purpose. The rows have already been read;
 * this is the source's <em>identity</em>, not a way to read it again.
 */
public record CapturedSqlSource(Path file, CaptureContext capture) implements SqlSource {

    @Override
    public Stream<SqlInput> getSql() {
        return Stream.empty();
    }

    /**
     * When sql-explorer first saw this data, which for a capture is when it was
     * taken — there is no separate discovery moment to distinguish it from.
     */
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

    /**
     * The origin the capture recorded, unchanged.
     *
     * <p>Not {@code PARQUET_COPY}. Origin means how the rows reached
     * sql-explorer, and re-reading our own file does not add a hop to the chain
     * that ends at {@code sys.x$statement_analysis} — a live read that was
     * written down and read back is still a live read. Overwriting it here
     * would make every rehydration look one step more distant than the one
     * before it.
     */
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
