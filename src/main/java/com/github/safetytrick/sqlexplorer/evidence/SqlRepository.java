package com.github.safetytrick.sqlexplorer.evidence;

import javax.validation.constraints.NotNull;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.model.SqlProfile;
import com.github.safetytrick.sqlexplorer.model.TableReference;

public interface SqlRepository {

    SqlProfile getById(@NotNull String id);

    Stream<SqlProfile> getTableQueries(@NotNull TableReference table);

    Stream<SqlProfile> getQueriesForColumn(@NotNull TableReference table, @NotNull String column);

    Stream<TableReference> getAllTableReferences();

    Stream<SqlProfile> getAllAnalyzedSql();

    SqlSource getSource();

    SqlImportStats getImportStats();
}
