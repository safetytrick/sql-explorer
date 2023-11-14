package com.github.safetytrick.sqlexplorer.service;

import java.util.stream.Stream;

import javax.validation.constraints.NotNull;

import com.github.safetytrick.sqlexplorer.core.AnalyzedSql;
import com.github.safetytrick.sqlexplorer.core.TableReference;
import com.github.safetytrick.sqlexplorer.source.SqlSource;

public interface SqlRepository {

    AnalyzedSql getById(@NotNull String id);

    Stream<AnalyzedSql> getTableQueries(@NotNull TableReference table);

    Stream<AnalyzedSql> getQueriesForColumn(@NotNull TableReference table, @NotNull String column);

    Stream<TableReference> getAllTableReferences();

    Stream<AnalyzedSql> getAllAnalyzedSql();

    SqlSource getSource();

    SqlImportStats getImportStats();
}
