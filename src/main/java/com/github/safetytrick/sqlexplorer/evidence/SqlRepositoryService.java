package com.github.safetytrick.sqlexplorer.evidence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.analysis.IgnoredSchemas;
import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.commentary.Tag;
import com.github.safetytrick.sqlexplorer.commentary.TagLoader;
import com.github.safetytrick.sqlexplorer.commentary.TagsService;
import com.github.safetytrick.sqlexplorer.model.SchemaRollup;
import com.github.safetytrick.sqlexplorer.model.Table;
import com.github.safetytrick.sqlexplorer.model.TableRollup;
import com.github.safetytrick.sqlexplorer.schema.ForeignKey;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaService;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import org.springframework.stereotype.Service;

@Service
public class SqlRepositoryService {

    private final SqlSchemaService sqlSchemaService;
    private final SqlRepositoryManager sqlRepositoryManager;
    private final TagsService tagsService;
    private final IgnoredSchemas ignoredSchemas;

    public SqlRepositoryService(SqlSchemaService sqlSchemaService,
                                SqlRepositoryManager sqlRepositoryManager,
                                TagsService tagsService,
                                IgnoredSchemas ignoredSchemas) {
        this.sqlSchemaService = sqlSchemaService;
        this.sqlRepositoryManager = sqlRepositoryManager;
        this.tagsService = tagsService;
        this.ignoredSchemas = ignoredSchemas;
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
            .filter(analyzedSql -> analyzedSql.getTables().stream().anyMatch(otherTable::equalsNormalized))
            .toList();

        return buildTableWithQueries(threshold, table, tableQueries);
    }

    private Table buildTableWithQueries(int threshold, TableReference table, List<SqlProfile> sqlList) {

        TableStructure tableStructure = sqlSchemaService.getTable(table);

        List<TableReference> foreignKeyTables = getForeignKeysTables(tableStructure);
        List<TableReference> joinTables = new ArrayList<>(deriveJoinTables(threshold, table, sqlList));
        List<TableReference> relatedTables = new ArrayList<>(relatedTablesFromQueries(threshold, table, sqlList));
        // todo, describe these multiple relationships instead of discarding them
        relatedTables.removeAll(joinTables);
        relatedTables.removeAll(foreignKeyTables);
        joinTables.removeAll(foreignKeyTables);

        return Table.builder().name(table.getNormalizedTableName())
            .schema(table.getNormalizedSchemaName())
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
        if (tableStructure == null) {
            return List.of();
        }
        return tableStructure.foreignKeys().stream()
            .map(ForeignKey::to)
            .map(List::getFirst)
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
            .flatMap(join -> Stream.of(join.getLeft(), join.getRight()))
            .map(ColumnReference::getTable)
            .filter(joinTable -> !table.equals(joinTable))
            .distinct()
            .toList();
    }

    private List<TableReference> relatedTablesFromQueries(int threshold, TableReference table, List<SqlProfile> sqlList) {
        return getAllJoinColumnsFromQueries(threshold, sqlList)
            .filter(join -> !isExternalReference(table, join))
            .flatMap(join -> Stream.of(join.getLeft(), join.getRight()))
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

        // todo, check that the pk for the table is named id !!!!!!! eff
        return !columnReference.getNormalizedColumn().equals("id");
    }

    public Stream<SqlProfile> getAllAnalyzedSql() {
        return sqlRepositoryManager.getSqlRepository().getAllAnalyzedSql();
    }

    public Stream<SchemaRollup> getAllSchemasWithRollup() {
        Map<String, SchemaRollup.Builder> result = new HashMap<>();
        sqlRepositoryManager.getSqlRepository().getAllTableReferences().forEach(
            table -> {
                SchemaRollup.Builder builder = result.computeIfAbsent(
                    table.getNormalizedSchemaName(), schemaName ->
                    SchemaRollup.builder().name(schemaName)
                );
                List<SqlProfile> sqlList = sqlRepositoryManager.getSqlRepository().getTableQueries(table)
                    .toList();

                double totalQueryTime = sqlList.stream()
                    .mapToDouble(analyzedSql -> analyzedSql.getRuntimeMetrics().getTotalTime().doubleValue())
                    .sum();
                builder.addQueries(sqlList.size(), totalQueryTime);
            }
        );

        return result.values().stream().map(builder-> {
            List<Tag> schemaTags = tagsService.findAllByPath("/schema/" + builder.getName());
            return builder.tags(schemaTags).build();
        }).sorted(SchemaRollup.comparator(ignoredSchemas));
    }

    public Stream<TableRollup> getAllTablesWithRollup() {

        TagLoader tagLoader = tagsService.getBulkTagLoaderByType("table");

        return sqlRepositoryManager.getSqlRepository().getAllTableReferences().map(
            reference -> getTableRollup(reference, tagLoader)
            ).sorted(TableRollup.comparator(ignoredSchemas));
    }

    public Stream<TableRollup> getTablesForSchemaWithRollup(String schema) {
        TagLoader tagLoader = tagsService.getBulkTagLoaderByTypeAndPathPrefix("table", "/schema/" + schema + "/");

        return sqlRepositoryManager.getSqlRepository().getAllTableReferences()
            .filter(table -> table.getNormalizedSchemaName() != null && table.getNormalizedSchemaName().equalsIgnoreCase(schema)).map(
                reference -> getTableRollup(reference, tagLoader)
            ).sorted(TableRollup.comparator(ignoredSchemas));
    }

    public TableRollup getTableRollup(TableReference reference, TagLoader tagLoader) {
        List<SqlProfile> sqlList = sqlRepositoryManager.getSqlRepository().getTableQueries(reference)
            .toList();

        double totalQueryTime = sqlList.stream()
            .mapToDouble(analyzedSql -> analyzedSql.getRuntimeMetrics().getTotalTime().doubleValue())
            .sum();

        return TableRollup.builder()
            .name(reference.getNormalizedTableName())
            .schema(reference.getNormalizedSchemaName())
            .tags(tagLoader.findAllByPath("/schema/" + reference.getNormalizedSchemaName() + "/table/" + reference.getNormalizedTableName()))
            .numberOfQueries(sqlList.size())
            .totalQueryTime(totalQueryTime)
            .build();
    }

    public TableRollup getTableRollup(TableReference reference) {
        return getTableRollup(reference, tagsService);
    }

}
