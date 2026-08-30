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
}
