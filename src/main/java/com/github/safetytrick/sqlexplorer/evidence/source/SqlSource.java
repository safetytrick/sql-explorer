package com.github.safetytrick.sqlexplorer.evidence.source;

import java.time.Instant;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonIgnore;

public interface SqlSource {
    @JsonIgnore
    Stream<SqlInput> getSql();
    Instant getCreatedOn();
    String getSourceRef();

    /**
     * When the rows were read off the source server, if that is known.
     *
     * <p>Distinct from {@link #getCreatedOn()}, which is when sql-explorer
     * first saw the source — for an uploaded export the two are routinely
     * months apart, and treating creation time as capture time would put a
     * confident wrong timestamp on the fact every coverage signal hangs off
     * (sqlx-sy5).
     *
     * <p>Null by default because most sources genuinely do not know: only an
     * uploaded export carries it, supplied by whoever ran the export.
     */
    default Instant getCapturedAt() {
        return null;
    }

    /**
     * What the source server was configured like when the rows were read —
     * digest table size, digest length limits, version, eviction counter.
     *
     * <p>Empty by default, and empty means <em>unknown</em>. It must never be
     * filled in later from a live connection: these values applied on that
     * server at that moment, and today's describe neither (sqlx-sy5).
     */
    default java.util.Map<String, String> getCaptureServerContext() {
        return java.util.Map.of();
    }

    /**
     * How rows from this source reached sql-explorer, or null when unknown.
     *
     * <p>Every source originates in {@code sys.x$statement_analysis}; what
     * differs is how many hands the rows passed through, and that is what
     * decides how much the capture-time settings can be trusted to describe
     * them (sqlx-sy5, sqlx-n01).
     */
    default com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext.Origin getCaptureOrigin() {
        return null;
    }
}
