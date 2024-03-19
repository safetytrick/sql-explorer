package com.github.safetytrick.sqlexplorer.evidence;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.model.ColumnReference;
import com.github.safetytrick.sqlexplorer.model.MergedSqlProfile;
import com.github.safetytrick.sqlexplorer.model.SqlProfile;
import com.github.safetytrick.sqlexplorer.model.TableReference;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import jakarta.validation.constraints.NotNull;

class AnalyzedSqlRepository implements SqlRepository {

    private final SqlSource source;
    private final SqlImportStats importStats;
    private final List<SqlProfile> sqlProfiles;
    private final Map<String, SqlProfile> analyzedSqlRepository;
    private final ImmutableListMultimap<TableReference, SqlProfile> tableNameAnalyzedSqlRepository;

    public AnalyzedSqlRepository(SqlSource source, SqlImportStats importStats, List<SqlProfile> sqlProfiles) {
        this.source = source;
        this.importStats = importStats;
        this.sqlProfiles = Collections.unmodifiableList(mergeByDigest(sqlProfiles));

        Map<String, SqlProfile> analyzedSqlRepository = new HashMap<>();
        for (SqlProfile sqlProfile : sqlProfiles) {
            List<String> perfDigests = sqlProfile.getPerfDigests();
            if(perfDigests != null) {
                for (String digest : perfDigests) {
                    analyzedSqlRepository.put(digest, sqlProfile);
                }
            }

            if (sqlProfile.getNormalizedDigest() != null) {
                analyzedSqlRepository.put(sqlProfile.getNormalizedDigest(), sqlProfile);
            }

            if (sqlProfile.getDigest() != null) {
                analyzedSqlRepository.put(sqlProfile.getDigest(), sqlProfile);
            }
        }

        this.analyzedSqlRepository = Collections.unmodifiableMap(analyzedSqlRepository);

        ImmutableListMultimap.Builder<TableReference, SqlProfile> tableNameAnalyzedSqlRepository = ImmutableListMultimap.builder();

        for (SqlProfile sqlProfile : sqlProfiles) {
            for (TableReference table : sqlProfile.getTables()) {

                tableNameAnalyzedSqlRepository.put(table.withDefaultSchema(sqlProfile.getSchema()), sqlProfile);
            }
        }

        this.tableNameAnalyzedSqlRepository = tableNameAnalyzedSqlRepository.build();
    }

    private List<SqlProfile> mergeByDigest(List<SqlProfile> sqlList) {
        Map<String, List<SqlProfile>> groupedByDigest = sqlList.stream()
            .collect(Collectors.groupingBy(this::getDigestOrId));

        return groupedByDigest.values()
            .stream()
            .map(this::mergedSql)
            .sorted()
            .collect(Collectors.toList());
    }

    private SqlProfile mergedSql(List<SqlProfile> alreadyGroupedList) {
        return alreadyGroupedList.size() > 1 ? new MergedSqlProfile(alreadyGroupedList) : alreadyGroupedList.get(0);
    }

    private String getDigestOrId(SqlProfile sqlProfile) {
        if (sqlProfile.getNormalizedDigest() != null) {
            return sqlProfile.getNormalizedDigest();
        } else if (sqlProfile.getDigest() != null) {
            return sqlProfile.getDigest();
        } else if (sqlProfile.getId() != null) {
            return sqlProfile.getId();
        }
        return sqlProfile.getQuery();
    }


    @Override
    public SqlProfile getById(@NotNull String id) {

        SqlProfile sqlProfile = analyzedSqlRepository.get(id);

        if (sqlProfile != null && sqlProfile.getNormalizedDigest() != null && !Objects.equals(id, sqlProfile.getNormalizedDigest())) {
            return getById(sqlProfile.getNormalizedDigest());
        }

        return sqlProfile;
    }

    @Override
    public Stream<SqlProfile> getTableQueries(TableReference table) {
        ImmutableList<SqlProfile> tableQueries = tableNameAnalyzedSqlRepository.get(table);
        return tableQueries != null ? tableQueries.stream() : Stream.empty();
    }

    @Override
    public Stream<SqlProfile> getQueriesForColumn(TableReference table, String column) {
        ColumnReference columnReference = new ColumnReference(table, column);
        Stream<SqlProfile> tableQueries = getTableQueries(table);
        return tableQueries.filter(sql -> sql.constrainedBy(columnReference));
    }

    @Override
    public Stream<TableReference> getAllTableReferences() {
        return tableNameAnalyzedSqlRepository.keys().stream();
    }

    @Override
    public Stream<SqlProfile> getAllAnalyzedSql() {
        return sqlProfiles.stream();
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
