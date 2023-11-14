package com.github.safetytrick.sqlexplorer.service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.core.AnalyzedSql;
import com.github.safetytrick.sqlexplorer.core.ColumnReference;
import com.github.safetytrick.sqlexplorer.core.JoinColumns;
import com.github.safetytrick.sqlexplorer.core.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.core.Table;
import com.github.safetytrick.sqlexplorer.core.TableReference;
import com.github.safetytrick.sqlexplorer.core.TableRollup;
import com.github.safetytrick.sqlexplorer.schema.SqlMetadataService;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import org.springframework.stereotype.Service;

import com.github.safetytrick.sqlexplorer.anno.TagLoader;
import com.github.safetytrick.sqlexplorer.anno.TagsService;

import static java.util.Comparator.comparing;

@Service
public class SqlRepositoryService {

    private final SqlMetadataService sqlMetadataService;
    private final SqlRepositoryManager sqlRepositoryManager;
    private final TagsService tagsService;

    public SqlRepositoryService(SqlMetadataService sqlMetadataService, SqlRepositoryManager sqlRepositoryManager, TagsService tagsService) {
        this.sqlMetadataService = sqlMetadataService;
        this.sqlRepositoryManager = sqlRepositoryManager;
        this.tagsService = tagsService;
    }

    public AnalyzedSql getById(String id) {
        return sqlRepositoryManager.getSqlRepository().getById(id);
    }

    // todo refactor threshold into a predicate to filter AnalyzedSql
    public Table getTable(int threshold, TableReference table) {
        return buildTableWithQueries(threshold, table, sqlRepositoryManager.getSqlRepository().getTableQueries(table)
            .collect(Collectors.toList()));
    }

    public Table getQueriesForColumn(int threshold, TableReference table, String column) {
        return buildTableWithQueries(threshold, table, sqlRepositoryManager.getSqlRepository().getQueriesForColumn(table, column)
            .collect(Collectors.toList()));
    }


    public Table getQueriesForTableJoin(int threshold, TableReference table, TableReference otherTable) {
        List<AnalyzedSql> tableQueries = sqlRepositoryManager.getSqlRepository().getTableQueries(table)
            .filter(analyzedSql -> analyzedSql.getTables().contains(otherTable))
            .collect(Collectors.toList());

        return buildTableWithQueries(threshold, table, tableQueries);
    }

    private Table buildTableWithQueries(int threshold, TableReference table, List<AnalyzedSql> sqlList) {

        TableStructure tableStructure = sqlMetadataService.getTable(table);

        List<TableReference> joinTables = deriveJoinTables(threshold, table, sqlList);
        List<TableReference> relatedTables = relatedTablesFromQueries(threshold, table, sqlList);
        relatedTables.removeAll(joinTables);

        return Table.builder().name(table.getNormalizedTableName())
            .schema(table.getNormalizedSchema())
            .queries(sqlList)
            .structure(tableStructure)
            .numberOfQueries(sqlList.size())
            .joinTables(joinTables)
            .relatedTables(relatedTables)
            .sumOfQueryTime(sqlList.stream()
                                .mapToDouble(analyzedSql -> analyzedSql.getRuntimeMetrics()
                                    .getTotalTime()
                                    .doubleValue())
                                .sum())
            .build();
    }

    private List<TableReference> deriveJoinTables(int threshold, TableReference table, List<AnalyzedSql> sqlList) {
        return getAllJoinColumnsFromQueries(threshold, sqlList)
            .filter(join -> isExternalReference(table, join))
            .flatMap(join -> Stream.of(join.getLeft(), join.getRight()))
            .map(ColumnReference::getTable)
            .filter(joinTable -> !table.equals(joinTable))
            .distinct()
            .collect(Collectors.toList());
    }

    private List<TableReference> relatedTablesFromQueries(int threshold, TableReference table, List<AnalyzedSql> sqlList) {
        return getAllJoinColumnsFromQueries(threshold, sqlList)
            .filter(join -> !isExternalReference(table, join))
            .flatMap(join -> Stream.of(join.getLeft(), join.getRight()))
            .map(ColumnReference::getTable)
            .filter(joinTable -> !table.equals(joinTable))
            .distinct()
            .collect(Collectors.toList());
    }

    private Stream<JoinColumns> getAllJoinColumnsFromQueries(int threshold, List<AnalyzedSql> sqlList) {
        boolean removeDormant = threshold > 0;
        return sqlList.stream().filter(
            sql -> (sql.getRuntimeMetrics() == null || sql.getRuntimeMetrics().getExecuted() > threshold)
                && (!removeDormant || sql.getQueryActivityStatus() != QueryActivityStatus.DORMANT)
                && sql.getJoinColumns() != null && !sql.getJoinColumns().isEmpty()
        ).map(AnalyzedSql::getJoinColumns).flatMap(List::stream);
    }

    private boolean isExternalReference(TableReference table, JoinColumns join) {
        if (join.getLeft().getTable().equals(join.getRight().getTable())) {
            return false;
        }

        ColumnReference columnReference;
        if (join.getLeft().getTable().equals(table)) {
            columnReference = join.getRight();
        } else if (join.getRight().getTable().equals(table)) {
            columnReference = join.getLeft();
        } else {
            // this is a join that doesn't involve the table we are interested in
            return false;
        }
        // todo, check that the pk for the table is named id
        return !columnReference.getNormalizedColumn().equals("id");
    }

    public Stream<AnalyzedSql> getAllAnalyzedSql() {
        return sqlRepositoryManager.getSqlRepository().getAllAnalyzedSql();
    }

    public Stream<TableRollup> getAllTablesWithRollup() {

        TagLoader tagLoader = tagsService.getBulkTagLoaderByType("table");

        return sqlRepositoryManager.getSqlRepository().getAllTableReferences().map(
            reference -> getTableRollup(reference, tagLoader)
        ).sorted(comparing(TableRollup::getTotalQueryTime).reversed());
    }

    public Stream<TableRollup> getTablesForSchemaWithRollup(String schema) {
        TagLoader tagLoader = tagsService.getBulkTagLoaderByTypeAndPathPrefix("table", "/schema/" + schema + "/");

        return sqlRepositoryManager.getSqlRepository().getAllTableReferences()
            .filter(table -> table.getNormalizedSchema() != null && table.getNormalizedSchema().equalsIgnoreCase(schema)).map(
                reference -> getTableRollup(reference, tagLoader)
        ).sorted(comparing(TableRollup::getTotalQueryTime).reversed());
    }

    public TableRollup getTableRollup(TableReference reference, TagLoader tagLoader) {
        List<AnalyzedSql> sqlList = sqlRepositoryManager.getSqlRepository().getTableQueries(reference)
            .collect(Collectors.toList());

        double totalQueryTime = sqlList.stream()
            .mapToDouble(analyzedSql -> analyzedSql.getRuntimeMetrics().getTotalTime().doubleValue())
            .sum();

        return TableRollup.builder()
            .name(reference.getNormalizedTableName())
            .schema(reference.getNormalizedSchema())
            .tags(tagLoader.findAllByPath("/schema/" + reference.getNormalizedSchema() + "/table/" + reference.getNormalizedTableName()))
            .numberOfQueries(sqlList.size())
            .sumOfQueryTime(totalQueryTime)
            .build();
    }

    public TableRollup getTableRollup(TableReference reference) {
        return getTableRollup(reference, tagsService);
    }
}
