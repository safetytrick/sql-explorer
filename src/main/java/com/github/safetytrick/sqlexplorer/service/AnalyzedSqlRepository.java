package com.github.safetytrick.sqlexplorer.service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.validation.constraints.NotNull;

import com.github.safetytrick.sqlexplorer.core.AnalyzedSql;
import com.github.safetytrick.sqlexplorer.core.ColumnReference;
import com.github.safetytrick.sqlexplorer.core.MergedAnalyzedSql;
import com.github.safetytrick.sqlexplorer.core.TableReference;
import com.github.safetytrick.sqlexplorer.source.SqlSource;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;

public class AnalyzedSqlRepository implements SqlRepository {

    private final SqlSource source;
    private final SqlImportStats importStats;
    private final List<AnalyzedSql> allAnalyzedSql;
    private final Map<String, AnalyzedSql> analyzedSqlRepository;
    private final ImmutableListMultimap<TableReference, AnalyzedSql> tableNameAnalyzedSqlRepository;

    public AnalyzedSqlRepository(SqlSource source, SqlImportStats importStats, List<AnalyzedSql> allAnalyzedSql) {
        this.source = source;
        this.importStats = importStats;
        this.allAnalyzedSql = Collections.unmodifiableList(mergeByDigest(allAnalyzedSql));

        Map<String,AnalyzedSql> analyzedSqlRepository = new HashMap<>();
        for (AnalyzedSql analyzedSql : allAnalyzedSql) {
            List<String> perfDigests = analyzedSql.getPerfDigests();
            if(perfDigests != null) {
                for (String digest : perfDigests) {
                    analyzedSqlRepository.put(digest, analyzedSql);
                }
            }

            if (analyzedSql.getNormalizedDigest() != null) {
                analyzedSqlRepository.put(analyzedSql.getNormalizedDigest(), analyzedSql);
            }

            if (analyzedSql.getDigest() != null) {
                analyzedSqlRepository.put(analyzedSql.getDigest(), analyzedSql);
            }
        }

        this.analyzedSqlRepository = Collections.unmodifiableMap(analyzedSqlRepository);

        ImmutableListMultimap.Builder<TableReference, AnalyzedSql> tableNameAnalyzedSqlRepository = ImmutableListMultimap.builder();

        for (AnalyzedSql analyzedSql : allAnalyzedSql) {
            for (TableReference table : analyzedSql.getTables()) {

                tableNameAnalyzedSqlRepository.put(table.withDefaultSchema(analyzedSql.getSchema()), analyzedSql);
            }
        }

        this.tableNameAnalyzedSqlRepository = tableNameAnalyzedSqlRepository.build();
    }

    private List<AnalyzedSql> mergeByDigest(List<AnalyzedSql> sqlList) {
        Map<String, List<AnalyzedSql>> groupedByDigest = sqlList.stream()
            .collect(Collectors.groupingBy(this::getDigestOrId));

        return groupedByDigest.values()
            .stream()
            .map(this::mergedSql)
            .sorted()
            .collect(Collectors.toList());
    }

    private AnalyzedSql mergedSql(List<AnalyzedSql> alreadyGroupedList) {
        return alreadyGroupedList.size() > 1 ? new MergedAnalyzedSql(alreadyGroupedList) : alreadyGroupedList.get(0);
    }

    private String getDigestOrId(AnalyzedSql analyzedSql) {
        if (analyzedSql.getNormalizedDigest() != null) {
            return analyzedSql.getNormalizedDigest();
        } else if (analyzedSql.getDigest() != null) {
            return analyzedSql.getDigest();
        } else if (analyzedSql.getId() != null) {
            return analyzedSql.getId();
        }
        return analyzedSql.getQuery();
    }


    @Override
    public AnalyzedSql getById(@NotNull String id) {

        AnalyzedSql analyzedSql = analyzedSqlRepository.get(id);

        if (analyzedSql != null && analyzedSql.getNormalizedDigest() != null && !Objects.equals(id, analyzedSql.getNormalizedDigest())) {
            return getById(analyzedSql.getNormalizedDigest());
        }

        return analyzedSql;
    }

    @Override
    public Stream<AnalyzedSql> getTableQueries(TableReference table) {
        ImmutableList<AnalyzedSql> tableQueries = tableNameAnalyzedSqlRepository.get(table);
        return tableQueries != null ? tableQueries.stream() : Stream.empty();
    }

    @Override
    public Stream<AnalyzedSql> getQueriesForColumn(TableReference table, String column) {
        ColumnReference columnReference = new ColumnReference(table, column);
        Stream<AnalyzedSql> tableQueries = getTableQueries(table);
        return tableQueries.filter(sql -> sql.constrainedBy(columnReference));
    }

    @Override
    public Stream<TableReference> getAllTableReferences() {
        return tableNameAnalyzedSqlRepository.keys().stream();
    }

    @Override
    public Stream<AnalyzedSql> getAllAnalyzedSql() {
        return allAnalyzedSql.stream();
    }

    @Override
    public SqlSource getSource() {
        return source;
    }

    @Override
    public SqlImportStats getImportStats() {
        return importStats;
    }
}
