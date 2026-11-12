package com.github.safetytrick.sqlexplorer.evidence;

import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import jakarta.validation.constraints.NotNull;

public interface SqlRepository {

    /**
     * The profile a digest names, or null.
     *
     * <p>A DIGEST, not the profile's {@code id} — which is why this is no
     * longer called {@code getById}. Nothing resolves by id: the in-memory
     * repository indexes profiles under their performance-schema digests, their
     * normalized digest and their {@code digest} field, and never under
     * {@code id}. The old name let two implementations of this one method mean
     * two different things — the heap one resolving digests, the parquet one
     * pushing an equality filter onto the id column — which behind
     * {@link CompositeSqlRepository} would have answered a digest from one
     * source and nothing from the other, silently (sqlx-40c).
     *
     * <p>Accepts any digest a profile carries, and follows through to the
     * merge-group representative when given a member's.
     */
    SqlProfile getByDigest(@NotNull String digest);

    Stream<SqlProfile> getTableQueries(@NotNull TableReference table);

    Stream<SqlProfile> getQueriesForColumn(@NotNull TableReference table, @NotNull String column);

    Stream<TableReference> getAllTableReferences();

    Stream<SqlProfile> getAllAnalyzedSql();

    SqlSource getSource();

    SqlImportStats getImportStats();

    /**
     * What this repository's source said about itself at capture time, or null
     * when nothing is known.
     *
     * <p>Per source, never aggregated: the digest limits, the observed window
     * and the origin all belong to one capture of one server, and folding two
     * together would describe neither (sqlx-sy5, sqlx-n01).
     *
     * <p>This is the same object written into the Parquet footer, computed
     * once during the import. Recomputing it later from the loaded profiles
     * would read a different population — merged representatives rather than
     * the rows that were written — so the file and the API could disagree
     * about the same capture.
     */
    default CaptureContext getCaptureContext() {
        return null;
    }

    /**
     * The source this repository holds, by ref: the capture's own record
     * first, then the source's. A rehydrated capture's source falls back to
     * its file name when it has no record, which the capture's ref corrects.
     */
    default String sourceRef() {
        CaptureContext capture = getCaptureContext();
        if (capture != null && capture.sourceRef() != null) {
            return capture.sourceRef();
        }
        SqlSource source = getSource();
        return source == null ? null : source.getSourceRef();
    }
}
