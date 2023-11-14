package com.github.safetytrick.sqlexplorer.service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.validation.constraints.NotNull;

import com.github.safetytrick.sqlexplorer.core.AnalyzedSql;
import com.github.safetytrick.sqlexplorer.core.MergedAnalyzedSql;
import com.github.safetytrick.sqlexplorer.core.TableReference;
import com.github.safetytrick.sqlexplorer.source.SqlSource;

public class CompositeSqlRepository implements SqlRepository {

    private final List<SqlRepository> sources;

    public CompositeSqlRepository(List<SqlRepository> sources) {
        this.sources = sources;
    }

    @Override
    public AnalyzedSql getById(@NotNull String id) {
        List<AnalyzedSql> items = sources.stream().map(repo -> repo.getById(id))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        return merge(items);
    }

    @Override
    public Stream<AnalyzedSql> getTableQueries(@NotNull TableReference table) {

        return mergeStream(sources.stream().flatMap(repo -> repo.getTableQueries(table)));
    }

    @Override
    public Stream<AnalyzedSql> getQueriesForColumn(@NotNull TableReference table, @NotNull String column) {
        return mergeStream(sources.stream().flatMap(repo -> repo.getQueriesForColumn(table, column)));
    }

    @Override
    public Stream<TableReference> getAllTableReferences() {
        return sources.stream().flatMap(SqlRepository::getAllTableReferences).distinct();
    }

    @Override
    public Stream<AnalyzedSql> getAllAnalyzedSql() {
        return mergeStream(sources.stream().flatMap(SqlRepository::getAllAnalyzedSql));
    }

    @Override
    public SqlSource getSource() {
        // todo implement composite source
        return sources.get(0).getSource();
    }

    private AnalyzedSql merge(List<AnalyzedSql> items) {
        if (items.isEmpty()) {
            return null;
        } else if (items.size() == 1) {
            return items.get(0);
        } else {
            return new MergedAnalyzedSql(items);
        }
    }

    private Stream<AnalyzedSql> mergeStream(Stream<AnalyzedSql> stream) {
        //noinspection ConstantConditions empty lists cannot appear in the grouping
        return stream.collect(
            Collectors.groupingBy(sql -> sql.getNormalizedDigest() != null ? sql.getNormalizedDigest() : sql.getDigest() != null ? sql.getDigest() : sql.getId()))
            .values()
            .stream()
            .map(this::merge).sorted();
    }

    @Override
    public SqlImportStats getImportStats() {
        return sources.stream().map(SqlRepository::getImportStats)
            .reduce(SqlImportStats.builder().build(), SqlImportStats::mergedWith);
    }
}
