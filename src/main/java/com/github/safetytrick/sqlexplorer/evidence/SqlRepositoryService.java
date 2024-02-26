package com.github.safetytrick.sqlexplorer.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.commentary.TagLoader;
import com.github.safetytrick.sqlexplorer.commentary.TagsService;
import com.github.safetytrick.sqlexplorer.model.ColumnReference;
import com.github.safetytrick.sqlexplorer.model.JoinColumns;
import com.github.safetytrick.sqlexplorer.model.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.model.SqlProfile;
import com.github.safetytrick.sqlexplorer.model.Table;
import com.github.safetytrick.sqlexplorer.model.TableReference;
import com.github.safetytrick.sqlexplorer.model.TableRollup;
import com.github.safetytrick.sqlexplorer.schema.ForeignKey;
import com.github.safetytrick.sqlexplorer.schema.SqlMetadataService;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import org.springframework.stereotype.Service;

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

    public SqlProfile getById(String id) {
        return sqlRepositoryManager.getSqlRepository().getById(id);
    }

    // todo refactor threshold into a predicate to filter AnalyzedSql
    public Table getTable(int threshold, TableReference table) {
        return buildTableWithQueries(threshold, table, sqlRepositoryManager.getSqlRepository().getTableQueries(table)
            .toList());
    }

    public Table getQueriesForColumn(int threshold, TableReference table, String column) {
        return buildTableWithQueries(threshold, table, sqlRepositoryManager.getSqlRepository().getQueriesForColumn(table, column)
            .toList());
    }


    public Table getQueriesForTableJoin(int threshold, TableReference table, TableReference otherTable) {
        List<SqlProfile> tableQueries = sqlRepositoryManager.getSqlRepository().getTableQueries(table)
            .filter(analyzedSql -> analyzedSql.getTables().contains(otherTable))
            .toList();

        return buildTableWithQueries(threshold, table, tableQueries);
    }

    private Table buildTableWithQueries(int threshold, TableReference table, List<SqlProfile> sqlList) {

        TableStructure tableStructure = sqlMetadataService.getTable(table);

        List<TableReference> foreignKeyTables = getForeignKeysTables(tableStructure);
        List<TableReference> joinTables = new ArrayList<>(deriveJoinTables(threshold, table, sqlList));
        List<TableReference> relatedTables = new ArrayList<>(relatedTablesFromQueries(threshold, table, sqlList));
        // todo, describe these multiple relationships instead of discarding them
        relatedTables.removeAll(joinTables);
        relatedTables.removeAll(foreignKeyTables);
        joinTables.removeAll(foreignKeyTables);

        return Table.builder().name(table.getNormalizedTableName())
            .schema(table.getNormalizedSchema())
            .queries(sqlList)
            .structure(tableStructure)
            .numberOfQueries(sqlList.size())
            .foreignKeyTables(foreignKeyTables)
            .joinTables(joinTables)
            .relatedTables(relatedTables)
            .sumOfQueryTime(sqlList.stream()
                                .mapToDouble(analyzedSql -> analyzedSql.getRuntimeMetrics()
                                    .getTotalTime()
                                    .doubleValue())
                                .sum())
            .build();
    }

    private List<TableReference> getForeignKeysTables(TableStructure tableStructure) {
        return tableStructure.foreignKeys().stream()
            .map(ForeignKey::to)
            .map(list -> list.get(0))
            .map(ColumnReference::getTable)
            .distinct()
            .toList();
    }

    /**
     * Does *NOT* return self-joins
     */
    private List<TableReference> deriveJoinTables(int threshold, TableReference table, List<SqlProfile> sqlList) {
        return getAllJoinColumnsFromQueries(threshold, sqlList)
            .filter(join -> isExternalReference(table, join))
            .flatMap(join -> Stream.of(join.left(), join.right()))
            .map(ColumnReference::getTable)
            .filter(joinTable -> !table.equals(joinTable))
            .distinct()
            .toList();
    }

    private List<TableReference> relatedTablesFromQueries(int threshold, TableReference table, List<SqlProfile> sqlList) {
        return getAllJoinColumnsFromQueries(threshold, sqlList)
            .filter(join -> !isExternalReference(table, join))
            .flatMap(join -> Stream.of(join.left(), join.right()))
            .map(ColumnReference::getTable)
            .filter(joinTable -> !table.equals(joinTable))
            .distinct()
            .toList();
    }

    private Stream<JoinColumns> getAllJoinColumnsFromQueries(int threshold, List<SqlProfile> sqlList) {
        boolean removeDormant = threshold > 0;
        return sqlList.stream().filter(
            sql -> (sql.getRuntimeMetrics() == null || sql.getRuntimeMetrics().getExecuted() > threshold)
                && (!removeDormant || sql.getQueryActivityStatus() != QueryActivityStatus.DORMANT)
                && sql.getJoinColumns() != null && !sql.getJoinColumns().isEmpty()
        ).map(SqlProfile::getJoinColumns).flatMap(List::stream);
    }

    private boolean isExternalReference(TableReference table, JoinColumns join) {
        if (join.left().getTable().equals(join.right().getTable())) {
            return false;
        }

        ColumnReference columnReference;
        if (join.left().getTable().equals(table)) {
            columnReference = join.right();
        } else if (join.right().getTable().equals(table)) {
            columnReference = join.left();
        } else {
            // this is a join that doesn't involve the table we are interested in
            return false;
        }
        // todo, check that the pk for the table is named id !!!!!!! eff
        return !columnReference.getNormalizedColumn().equals("id");
    }

    public Stream<SqlProfile> getAllAnalyzedSql() {
        return sqlRepositoryManager.getSqlRepository().getAllAnalyzedSql();
    }

    public Stream<TableRollup> getAllTablesWithRollup() {

        TagLoader tagLoader = tagsService.getBulkTagLoaderByType("table");

        return sqlRepositoryManager.getSqlRepository().getAllTableReferences().map(
            reference -> getTableRollup(reference, tagLoader)
        ).sorted(comparing(TableRollup::totalQueryTime).reversed());
    }

    public Stream<TableRollup> getTablesForSchemaWithRollup(String schema) {
        TagLoader tagLoader = tagsService.getBulkTagLoaderByTypeAndPathPrefix("table", "/schema/" + schema + "/");

        return sqlRepositoryManager.getSqlRepository().getAllTableReferences()
            .filter(table -> table.getNormalizedSchema() != null && table.getNormalizedSchema().equalsIgnoreCase(schema)).map(
                reference -> getTableRollup(reference, tagLoader)
        ).sorted(comparing(TableRollup::totalQueryTime).reversed());
    }

    public TableRollup getTableRollup(TableReference reference, TagLoader tagLoader) {
        List<SqlProfile> sqlList = sqlRepositoryManager.getSqlRepository().getTableQueries(reference)
            .toList();

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
