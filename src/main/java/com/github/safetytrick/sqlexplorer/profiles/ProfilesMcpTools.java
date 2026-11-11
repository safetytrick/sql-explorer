package com.github.safetytrick.sqlexplorer.profiles;

import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.mcp.McpArgs;
import com.github.safetytrick.sqlexplorer.mcp.McpTool;
import com.github.safetytrick.sqlexplorer.mcp.McpToolProvider;
import org.springframework.stereotype.Component;

/** The queries and their rollups over schemas and tables, as MCP tools. */
@Component
class ProfilesMcpTools implements McpToolProvider {

    /** The canned reports {@code /analyzed-sql/reports/{name}} answers. */
    static final List<String> REPORTS = List.of("all", "join-across-types", "slow", "chatty", "analysis-failures",
        "analysis-skipped", "parse-failures", "truncated", "full-scan", "multi-schema");

    private final SqlProfileResource profiles;
    private final SchemaTreeResource schemaTree;
    private final TableInsightsResource tableInsights;
    private final TableWorkloadResource tableWorkload;
    private final QuerySearchResource search;
    private final SqlProfileReportResource reports;

    ProfilesMcpTools(SqlProfileResource profiles, SchemaTreeResource schemaTree, TableInsightsResource tableInsights,
                     TableWorkloadResource tableWorkload, QuerySearchResource search, SqlProfileReportResource reports) {
        this.profiles = profiles;
        this.schemaTree = schemaTree;
        this.tableInsights = tableInsights;
        this.tableWorkload = tableWorkload;
        this.search = search;
        this.reports = reports;
    }

    @Override
    public List<McpTool> tools() {
        return List.of(
            McpTool.named("list_schemas", "Schemas seen in the analyzed queries, with query counts and total query time.")
                .handler(args -> McpArgs.listOf(profiles.getSchemas())),
            McpTool.named("list_tables", "Tables ranked by total query time. Optionally scoped to one schema. "
                    + "Each row carries query count, tags, whether it is a view, and rowsEstimate (information_schema "
                    + "TABLE_ROWS: an estimate, not a count) and sizeBytes (data + index), summed over shards.")
                .optionalString("schema", "scope to this schema")
                .handler(args -> {
                    String schema = args.optionalString("schema");
                    return McpArgs.listOf(schema == null || schema.isBlank()
                        ? profiles.getTableNames(null) : profiles.getTableNamesForSchema(schema, null));
                }),
            McpTool.named("schema_tree", "Every known schema and table (from metadata and queries) with column counts, "
                    + "query counts, analyzer-warning and view flags.")
                .handler(args -> schemaTree.getSchemaTree()),
            McpTool.named("get_table_stats",
                    "Aggregate query stats for one table: distinct queries, executions, total time, read/write split; "
                    + "writeRate (INSERT/UPDATE/DELETE executions and rows affected per day, by type and statement — "
                    + "daily AVERAGES over each statement's captured window, floored at one day); and lockRisk "
                    + "(statements ranked by max time, with how often they run: what a DDL would wait behind, "
                    + "holding every later query on the table behind it). Times in ms. Captured statistics have no "
                    + "time-of-day distribution, so quiet hours are not available.")
                .string("schema", null).string("table", null)
                .handler(args -> {
                    String schema = args.string("schema");
                    String table = args.string("table");
                    TableInsightsService.TableStats stats = tableInsights.getStats(schema, table);
                    return new TableStatsWithWorkload(stats.distinctQueries(), stats.totalExecutions(),
                        stats.totalTimeMs(), stats.readExecutions(), stats.writeExecutions(),
                        tableWorkload.getWriteRate(schema, table), tableWorkload.getLockRisk(schema, table));
                }),
            McpTool.named("get_column_usage", "Per-column usage for one table: type, PK/FK, query roles "
                    + "(select/criteria/join/group by/order by), query counts, how criteria compare each column "
                    + "(equality/range/in/like/...), and LIKE usage with leading-wildcard counts.")
                .string("schema", null).string("table", null)
                .handler(args -> tableInsights.getColumns(args.string("schema"), args.string("table"))),
            McpTool.named("search_queries", "Search analyzed queries with the workbench's filter engine. All filters "
                    + "optional. Returns matching profiles (normalized SQL, tables, join/criteria columns, runtime "
                    + "metrics) plus table/schema facet counts.")
                .optionalString("queryRegex",
                    "case-insensitive regex over the SQL text (invalid patterns fall back to literal search)")
                .optionalStrings("types", "statement types: SELECT, INSERT, UPDATE, DELETE, OTHER")
                .optionalStrings("excludeTypes", "statement types to leave out")
                .optionalStrings("schemas", "only queries in these schemas")
                .optionalStrings("excludeSchemas", "leave out queries in these schemas")
                .optionalStrings("tables", "\"schema.table\" keys the query must reference")
                .optionalStrings("excludeTables", "\"schema.table\" keys the query must not reference")
                .optionalEnum("tableMode", "whether a query must reference any or all of `tables`", List.of("ANY", "ALL"))
                .optionalStrings("tags", "query tags to require")
                .optionalStrings("excludeTags", "query tags to leave out")
                .optionalEnum("fullScan", "queries with (YES) or without (NO) full table scans", List.of("YES", "NO"))
                .optionalBoolean("analysisFailed", "only queries the analyzer could not analyze")
                .optionalEnum("sort", "default IMPACT",
                    List.of("IMPACT", "AVG_TIME", "MAX_TIME", "AVG_ROWS_SCANNED", "SELECTIVITY", "LAST_SEEN"))
                .optionalInteger("limit", "default 25", 1, 100)
                .optionalInteger("offset", null, 0, Integer.MAX_VALUE)
                .handler(args -> search.search(args.as(QuerySearchRequest.class, Map.of("limit", 25)))),
            McpTool.named("get_query", "One analyzed query profile by id or digest: normalized SQL, tables, "
                    + "join/criteria/select columns, runtime metrics, collapsed repeats.")
                .string("id", "profile id or digest")
                .handler(args -> profiles.getSql(args.string("id"))),
            McpTool.named("run_report", "A canned query report.")
                .requiredEnum("name", null, REPORTS)
                .optionalInteger("limit", "default 10", 1, 100)
                .handler(args -> {
                    Integer limit = args.optionalInteger("limit");
                    return McpArgs.listOf(reports.getNamedReport(args.string("name"), limit != null ? limit : 10, 0));
                })
        );
    }

    /** {@code get_table_stats}: the stat strip, flattened as it always was, plus write rate and lock risk. */
    public record TableStatsWithWorkload(int distinctQueries, long totalExecutions, double totalTimeMs,
                                  long readExecutions, long writeExecutions,
                                  TableWorkloadService.WriteRate writeRate,
                                  TableWorkloadService.LockRisk lockRisk) { }
}
