package com.github.safetytrick.sqlexplorer.evidence;

import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import jakarta.validation.constraints.NotNull;

public interface SqlRepository {

    SqlProfile getById(@NotNull String id);

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
}
