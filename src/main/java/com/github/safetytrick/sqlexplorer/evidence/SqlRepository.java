package com.github.safetytrick.sqlexplorer.evidence;

import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import jakarta.validation.constraints.NotNull;

public interface SqlRepository {

    SqlProfile getByDigest(@NotNull String digest);

    Stream<SqlProfile> getTableQueries(@NotNull TableReference table);

    Stream<SqlProfile> getQueriesForColumn(@NotNull TableReference table, @NotNull String column);

    Stream<TableReference> getAllTableReferences();

    Stream<SqlProfile> getAllAnalyzedSql();

    SqlSource getSource();

    SqlImportStats getImportStats();

    default CaptureContext getCaptureContext() {
        return null;
    }

    default String sourceRef() {
        CaptureContext capture = getCaptureContext();
        if (capture != null && capture.sourceRef() != null) {
            return capture.sourceRef();
        }
        SqlSource source = getSource();
        return source == null ? null : source.getSourceRef();
    }
}
