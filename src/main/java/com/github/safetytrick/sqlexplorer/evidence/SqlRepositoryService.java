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
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.commentary.Tag;
import com.github.safetytrick.sqlexplorer.commentary.TagLoader;
import com.github.safetytrick.sqlexplorer.commentary.TagTarget;
import com.github.safetytrick.sqlexplorer.commentary.TagsService;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;
import com.github.safetytrick.sqlexplorer.model.SchemaRollup;
import com.github.safetytrick.sqlexplorer.model.Table;
import com.github.safetytrick.sqlexplorer.model.CapturedRate;
import com.github.safetytrick.sqlexplorer.model.TableRollup;
import com.github.safetytrick.sqlexplorer.schema.ForeignKey;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaService;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import org.springframework.stereotype.Service;

@Service
public class SqlRepositoryService {

    private final SqlSchemaService sqlSchemaService;
    private final SqlRepositoryManager sqlRepositoryManager;
    private final EnvironmentContext environmentContext;
    private final TagsService tagsService;
    private final IgnoredSchemas ignoredSchemas;

    public SqlRepositoryService(SqlSchemaService sqlSchemaService,
                                SqlRepositoryManager sqlRepositoryManager,
                                EnvironmentContext environmentContext,
                                TagsService tagsService,
                                IgnoredSchemas ignoredSchemas) {
        this.sqlSchemaService = sqlSchemaService;
        this.sqlRepositoryManager = sqlRepositoryManager;
        this.environmentContext = environmentContext;
        this.tagsService = tagsService;
        this.ignoredSchemas = ignoredSchemas;
    }

    private SqlRepository repository() {
        return sqlRepositoryManager.getSqlRepository(environmentContext.current().getName());
    }

    public SqlProfile getByDigest(String id) {
        return repository().getByDigest(id);
    }

    public Table getTable(int threshold, TableReference table) {
        return buildTableWithQueries(threshold, table, repository().getTableQueries(table)
            .toList());
    }

    public Table getQueriesForColumn(int threshold, TableReference table, String column) {
        return buildTableWithQueries(threshold, table, repository().getQueriesForColumn(table, column)
            .toList());
    }


    public Table getQueriesForTableJoin(int threshold, TableReference table, TableReference otherTable) {
        List<SqlProfile> tableQueries = repository().getTableQueries(table)
            .filter(analyzedSql -> analyzedSql.getTables().stream().anyMatch(otherTable::equalsNormalized))
            .toList();

        return buildTableWithQueries(threshold, table, tableQueries);
    }

    private Table buildTableWithQueries(int threshold, TableReference table, List<SqlProfile> sqlList) {

        TableStructure tableStructure = sqlSchemaService.getTable(table);

        List<TableReference> foreignKeyTables = getForeignKeysTables(tableStructure);
        List<TableReference> joinTables = new ArrayList<>(deriveJoinTables(threshold, table, sqlList));
        List<TableReference> relatedTables = new ArrayList<>(relatedTablesFromQueries(threshold, table, sqlList));
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
                                .mapToDouble(SqlRepositoryService::totalQueryTimeOf)
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
            return false;
        }

        return !columnReference.getNormalizedColumn().equals("id");
    }

    private static double totalQueryTimeOf(SqlProfile profile) {
        RuntimeMetrics metrics = profile.getRuntimeMetrics();
        return metrics == null || metrics.getTotalTime() == null ? 0d : metrics.getTotalTime().doubleValue();
    }

    public Stream<SqlProfile> getAllAnalyzedSql() {
        return repository().getAllAnalyzedSql();
    }

    public Stream<SqlProfile> getAllAnalyzedSql(SourceScope scope) {
        return sqlRepositoryManager.getSqlRepository(environmentContext.current().getName(), scope)
            .getAllAnalyzedSql();
    }

    public Stream<SqlProfile> getTableQueries(TableReference table) {
        return repository().getTableQueries(table);
    }

    public Stream<SchemaRollup> getAllSchemasWithRollup() {
        Map<String, SchemaRollup.Builder> result = new HashMap<>();
        repository().getAllTableReferences().forEach(
            table -> {
                SchemaRollup.Builder builder = result.computeIfAbsent(
                    table.getNormalizedSchemaName(), schemaName ->
                    SchemaRollup.builder().name(schemaName)
                );
                List<SqlProfile> sqlList = repository().getTableQueries(table)
                    .toList();

                double totalQueryTime = sqlList.stream()
                    .mapToDouble(SqlRepositoryService::totalQueryTimeOf)
                    .sum();
                builder.addQueries(sqlList.size(), totalQueryTime);
            }
        );

        return result.values().stream().map(builder-> {
            List<Tag> schemaTags = tagsService.findAllByTarget(TagTarget.ofSchema(builder.getName()));
            return builder.tags(schemaTags).build();
        }).sorted(SchemaRollup.comparator(ignoredSchemas));
    }

    public SchemaRollup getSchemaRollup(String schemaName) {
        SchemaRollup.Builder builder = SchemaRollup.builder().name(schemaName);
        repository().getAllTableReferences()
            .filter(table -> schemaName.equalsIgnoreCase(table.getNormalizedSchemaName()))
            .forEach(table -> {
                List<SqlProfile> sqlList = repository().getTableQueries(table).toList();
                double totalQueryTime = sqlList.stream()
                    .mapToDouble(SqlRepositoryService::totalQueryTimeOf)
                    .sum();
                builder.addQueries(sqlList.size(), totalQueryTime);
            });
        List<Tag> schemaTags = tagsService.findAllByTarget(TagTarget.ofSchema(schemaName));
        return builder.tags(schemaTags).build();
    }

    public Stream<TableRollup> getAllTablesWithRollup(List<String> columns) {

        TagLoader tagLoader = tagsService.getBulkTagLoaderByType("table");

        return repository().getAllTableReferences()
            .filter(reference -> hasColumns(reference, columns))
            .map(reference -> getTableRollup(reference, tagLoader)
            ).sorted(TableRollup.comparator(ignoredSchemas));
    }

    public Stream<TableRollup> getTablesForSchemaWithRollup(String schema, List<String> columns) {
        TagLoader tagLoader = tagsService.getTableTagLoaderForSchema(schema);

        return repository().getAllTableReferences()
            .filter(table -> table.getNormalizedSchemaName() != null && table.getNormalizedSchemaName().equalsIgnoreCase(schema))
            .filter(reference -> hasColumns(reference, columns))
            .map(reference -> getTableRollup(reference, tagLoader)
            ).sorted(TableRollup.comparator(ignoredSchemas));
    }

    private boolean hasColumns(TableReference reference, List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return true;
        }
        TableStructure structure = sqlSchemaService.getTable(reference);
        if (structure == null || structure.columns() == null) {
            return false;
        }
        return columns.stream().allMatch(wanted -> structure.columns().stream()
            .anyMatch(column -> column.columnName().equalsIgnoreCase(wanted.strip())));
    }

    public TableRollup getTableRollup(TableReference reference, TagLoader tagLoader) {
        List<SqlProfile> sqlList = repository().getTableQueries(reference)
            .toList();

        double totalQueryTime = sqlList.stream()
            .mapToDouble(SqlRepositoryService::totalQueryTimeOf)
            .sum();

        TableStructure structure = sqlSchemaService.getTable(reference);

        return TableRollup.builder()
            .name(reference.getNormalizedTableName())
            .schema(reference.getNormalizedSchemaName())
            .tags(tagLoader.findAllByTarget(TagTarget.ofTable(
                reference.getNormalizedSchemaName(), reference.getNormalizedTableName())))
            .numberOfQueries(sqlList.size())
            .totalQueryTime(totalQueryTime)
            .view(structure != null && "VIEW".equalsIgnoreCase(structure.type()))
            .writesPerDay(CapturedRate.writesPerDay(reference, sqlList))
            .rowsEstimate(structure == null ? null : structure.rowsEstimate())
            .sizeBytes(structure == null ? null : structure.sizeBytes())
            .build();
    }

    public TableRollup getTableRollup(TableReference reference) {
        return getTableRollup(reference, tagsService);
    }

}
