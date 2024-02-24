package com.github.safetytrick.sqlexplorer.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.anno.SqlTagsService;
import com.github.safetytrick.sqlexplorer.core.AnalyzedSql;
import com.github.safetytrick.sqlexplorer.core.CrossSchemaTable;
import com.github.safetytrick.sqlexplorer.core.Table;
import com.github.safetytrick.sqlexplorer.core.TableReference;
import com.github.safetytrick.sqlexplorer.schema.SqlMetadataService;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import com.github.safetytrick.sqlexplorer.service.SqlRepositoryService;
import org.springframework.stereotype.Service;

@Service
public class SqlGraphService implements GraphService {

    private final SqlRepositoryService sqlRepositoryService;
    private final SqlMetadataService sqlMetadataService;
    private final SqlTagsService sqlTagsService;

    private static final String BASE_URL = "http://localhost:4200/analyzed-sql/";
    // todo refactor into a grafana integration plugin
    private static final String GRAFANA_URL = "https://grafana.local/d/dvUfZxpZk/sql-analysis?orgId=1&var-digest=%s&var-instance=All&var-interval=$__auto_interval_interval&from=now-30d&to=now";
    private static final String RUNDECK_QUERY = "https://rundeck.local/project/Dev/job/show/2cd8bed4-9df7-4c59-97ba-21685bc924fb?opt.Query=select digest_text from performance_schema.events_statements_summary_by_digest where digest = '%s' limit 1&opt.Server=%s";
    private static final String LIST_FORMAT = "%s,%s";
    private final Pattern serverPattern = Pattern.compile("SqlSource\\{sourceRef='(.+?)'");

    private static final Set<String> ignoredSchemas = new HashSet<>(Arrays.asList(
        "sys", "performance_schema", "mysql", "information_schema", "percona"
    ));

    public SqlGraphService(SqlRepositoryService sqlRepositoryService, SqlMetadataService sqlMetadataService, SqlTagsService sqlTagsService) {
        this.sqlRepositoryService = sqlRepositoryService;
        this.sqlMetadataService = sqlMetadataService;
        this.sqlTagsService = sqlTagsService;
    }

    @Override
    public Stream<TableRelationships> getTableRelationships(int threshold) {
        return sqlMetadataService.getTables().stream()
            .filter(table -> !ignored(table.schema())).map(table -> getTableRelationships(threshold, table));
    }

    @Override
    public Stream<TableRelationships> getTableRelationshipsForSchema(int threshold, String schema) {
        return sqlMetadataService.getTables().stream()
            .filter(table -> schema.equalsIgnoreCase(table.schema())).map(table -> getTableRelationships(threshold, table));
    }

    @Override
    public Stream<TableRelationships> getTableRelationshipsForSchemaAndTable(int threshold,
                                                                             int depth,
                                                                             String schema,
                                                                             String tableName) {
        Set<TableReference> visited = new HashSet<>();
        TableReference tableReference = new TableReference(schema, tableName);

        return visitRelated(visited, tableReference, threshold, depth);
    }

    @Override
    public Stream<GroupedTableRelationships> getTableRelationshipsForTag(int threshold, int depth, String tag) {
        return sqlTagsService.getTablesByTag(tag)
            .flatMap(table -> {
                        TableReference tableReference = new TableReference(table.schema(), table.name());
                         return getTableRelationshipsForSchemaAndTable(threshold, depth, table.schema(), table.name())
                             .map(tableRelationships -> tableRelationships.table().equals(tableReference)
                                 ? new GroupedTableRelationships(tag, tableRelationships)
                                 : new GroupedTableRelationships(tableReference.getSchema(), tableRelationships));

                     }

            );

    }

    /**
     * Returns a deduped list of the queries determined to use tables from the {@code tag} and make cross schema joins
     */
    @Override
    public List<CrossSchemaTable> getInfoForCrossSchemaJoins(String schema,
                                                             String tag,
                                                             String otherSchema,
                                                             int graphServiceThreshold,
                                                             int graphServiceDepth,
                                                             int threshold) {
        final Stream<GroupedTableRelationships> tableRelationshipsForTag = getTableRelationshipsForTag(
                graphServiceThreshold, graphServiceDepth, tag);
        final List<GroupedTableRelationships> groupedTableRelationships = tableRelationshipsForTag.toList();

        Map<String, CrossSchemaTable> queryToTables = new HashMap<>();
        final List<String> tablesWithinSameSchema = groupedTableRelationships.stream()
                .map(query -> query.table().getNormalizedTableName())
                .toList();
        for(GroupedTableRelationships groupedTableRelationship : groupedTableRelationships){
            String table = groupedTableRelationship.table().getNormalizedTableName();
            final List<TableReference> tableReferences = Stream.concat(groupedTableRelationship.foreignKeyTables().stream(),
                                                             Stream.concat(groupedTableRelationship.joinTables().stream(),
                                                                 groupedTableRelationship.relatedTables().stream()))
                    .filter(query -> !tablesWithinSameSchema.contains(query.getNormalizedTableName()))
                    .toList();
            for (TableReference tableReference : tableReferences) {
                String other = tableReference.getNormalizedTableName();
                final Table queriesForTableJoin = sqlRepositoryService.getQueriesForTableJoin(threshold,
                                                                                              new TableReference(schema,
                                                                                                                 table),
                                                                                              new TableReference(otherSchema,
                                                                                                                 other));
                final String tableValue = other + "|" + table;
                populateTablesFromQueries(queryToTables, tableValue, queriesForTableJoin.queries());
            }
        }
        return new ArrayList<>(queryToTables.values());
    }

    private void populateTablesFromQueries(Map<String, CrossSchemaTable> queryToTables,
                                           String tableValue,
                                           List<AnalyzedSql> queries) {
        for(AnalyzedSql analyzedSql : queries) {
            if(analyzedSql.getQueryActivityStatus().isActive()){
                String query = analyzedSql.getQuery().replaceAll("`", "").replaceAll("\\n\\s*", " ");
                final CrossSchemaTable currentTableValues = queryToTables.get(query);
                queryToTables.put(query, createCrossSchemaTable(tableValue, query, analyzedSql, currentTableValues));
            }
        }
    }

    private CrossSchemaTable createCrossSchemaTable(String tableValue, String query, AnalyzedSql analyzedSql, CrossSchemaTable existingTableValues){
        final String grafanaUrl = String.format(GRAFANA_URL, analyzedSql.getPerfDigests().get(0));
        Matcher matcher = serverPattern.matcher(analyzedSql.getId());
        String server = matcher.find() ? matcher.group(1) : "";
        final String rundeckQuery = String.format(RUNDECK_QUERY, analyzedSql.getPerfDigests().get(0), server);
        final String analysisUrl = BASE_URL + analyzedSql.getDigest();
        if(existingTableValues == null){
            return new CrossSchemaTable(
                    query, analyzedSql.getId(),
                    analysisUrl, grafanaUrl, rundeckQuery, tableValue);
        }
        return new CrossSchemaTable(
                query, concatenate(existingTableValues.analysisUrl(), analyzedSql.getId()),
                analysisUrl, concatenate(existingTableValues.grafanaUrl(), grafanaUrl),
                concatenate(existingTableValues.rundeckQuery(),rundeckQuery),
                concatenate(existingTableValues.tables(), tableValue));
    }

    private String concatenate(String currentList, String addition){
        return String.format(LIST_FORMAT, currentList, addition.replaceAll("`", ""));
    }

    private TableRelationships getTableRelationships(int threshold, TableStructure tableStructure) {
        TableReference reference = new TableReference(tableStructure.schema(), tableStructure.name());
        Table table = sqlRepositoryService.getTable(threshold, reference);
        return new TableRelationships(reference, table.foreignKeyTables(), table.joinTables(), table.relatedTables());
    }

    private boolean ignored(String schema) {
        return ignoredSchemas.contains(schema);
    }

    private Stream<TableRelationships> visitRelated(Set<TableReference> visited, TableReference tableReference, int threshold, int depth) {
        if (visited.contains(tableReference)) {
            return Stream.empty();
        }

        visited.add(tableReference);
        Table table = sqlRepositoryService.getTable(threshold, tableReference);

        TableRelationships relationships = new TableRelationships(tableReference, table.foreignKeyTables(), table.joinTables(), table.relatedTables());

        if (depth > 0) {
            return Stream.concat(Stream.of(relationships),
                Stream.concat(relationships.foreignKeyTables().stream(), relationships.joinTables().stream())
                    .flatMap(related -> visitRelated(visited, related, threshold, depth - 1)));
        }

        return Stream.of(relationships);

    }
}
