package com.github.safetytrick.sqlexplorer.evidence;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlInput;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.model.MergedSqlProfile;
import extensions.com.github.safetytrick.sqlexplorer.avro.SqlProfile.SqlProfileExtension;
import jakarta.validation.constraints.NotNull;

class CompositeSqlRepository implements SqlRepository {

    private volatile List<SqlProfile> mergedCorpus;

    private final List<SqlRepository> sources;

    public CompositeSqlRepository(List<SqlRepository> sources) {
        this.sources = sources;
    }

    @Override
    public SqlProfile getByDigest(@NotNull String id) {
        List<SqlProfile> items = sources.stream().map(repo -> repo.getByDigest(id))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        return merge(items);
    }

    @Override
    public Stream<SqlProfile> getTableQueries(@NotNull TableReference table) {

        return mergeStream(sources.stream().flatMap(repo -> repo.getTableQueries(table)));
    }

    @Override
    public Stream<SqlProfile> getQueriesForColumn(@NotNull TableReference table, @NotNull String column) {
        return mergeStream(sources.stream().flatMap(repo -> repo.getQueriesForColumn(table, column)));
    }

    @Override
    public Stream<TableReference> getAllTableReferences() {
        return sources.stream().flatMap(SqlRepository::getAllTableReferences).distinct();
    }

    @Override
    public Stream<SqlProfile> getAllAnalyzedSql() {
        List<SqlProfile> corpus = mergedCorpus;
        if (corpus == null) {
            synchronized (this) {
                corpus = mergedCorpus;
                if (corpus == null) {
                    corpus = mergeStream(sources.stream().flatMap(SqlRepository::getAllAnalyzedSql)).toList();
                    mergedCorpus = corpus;
                }
            }
        }
        return corpus.stream();
    }

    @Override
    public SqlSource getSource() {
        return new CompositeSqlSource(sources.stream()
            .map(SqlRepository::getSource)
            .filter(Objects::nonNull)
            .toList());
    }

    private record CompositeSqlSource(List<SqlSource> members) implements SqlSource {

        @Override
        public Stream<SqlInput> getSql() {
            return members.stream().flatMap(SqlSource::getSql);
        }

        @Override
        public Instant getCreatedOn() {
            return members.stream()
                .map(SqlSource::getCreatedOn)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        }

        @Override
        public String getSourceRef() {
            return members.stream()
                .map(SqlSource::getSourceRef)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("+"));
        }
    }

    private SqlProfile merge(List<SqlProfile> items) {
        if (items.isEmpty()) {
            return null;
        } else if (items.size() == 1) {
            return items.getFirst();
        } else {
            return new MergedSqlProfile(items);
        }
    }

    private Stream<SqlProfile> mergeStream(Stream<SqlProfile> stream) {
        return stream.collect(
            Collectors.groupingBy(sql -> sql.getNormalizedDigest() != null ? sql.getNormalizedDigest() : sql.getDigest() != null ? sql.getDigest() : sql.getId()))
            .values()
            .stream()
            .map(this::merge).sorted(SqlProfileExtension.comparator);
    }

    @Override
    public SqlImportStats getImportStats() {
        return sources.stream().map(SqlRepository::getImportStats)
            .reduce(SqlImportStats.builder().build(), SqlImportStats::mergedWith);
    }
}
