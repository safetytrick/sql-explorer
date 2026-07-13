package com.github.safetytrick.sqlexplorer.profiles;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryService;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaService;
import com.github.safetytrick.sqlexplorer.schema.TableColumn;
import com.github.safetytrick.sqlexplorer.schema.TableIndex;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import org.springframework.stereotype.Service;

/**
 * Table-detail rollups for the Schema view: per-column metadata merged with
 * query usage (roles + counts), and the table stat strip.
 */
@Service
public class TableInsightsService {

    private static final double PICOS_PER_MILLI = 1_000_000_000d;
    private static final String PRIMARY_INDEX = "PRIMARY";

    static final String ROLE_JOIN = "join";
    static final String ROLE_CRITERIA = "criteria";
    static final String ROLE_SELECT = "select";
    static final String ROLE_GROUP_BY = "group by";

    private final SqlRepositoryService sqlRepositoryService;
    private final SqlSchemaService sqlSchemaService;

    public TableInsightsService(SqlRepositoryService sqlRepositoryService, SqlSchemaService sqlSchemaService) {
        this.sqlRepositoryService = sqlRepositoryService;
        this.sqlSchemaService = sqlSchemaService;
    }

    /** Columns of the table with metadata attributes and how queries use them. */
    public List<ColumnUsage> getColumns(TableReference table) {
        TableStructure structure = sqlSchemaService.getTable(table);
        if (structure == null || structure.columns() == null) {
            return List.of();
        }

        Set<String> primaryKey = new HashSet<>();
        for (TableIndex index : structure.indices()) {
            if (PRIMARY_INDEX.equalsIgnoreCase(index.name())) {
                index.columns().forEach(column -> primaryKey.add(lower(column)));
            }
        }
        Set<String> foreignKey = new HashSet<>();
        structure.foreignKeys().forEach(fk -> fk.from()
            .forEach(column -> foreignKey.add(lower(column.getColumn()))));

        Map<String, Usage> usage = collectUsage(table);

        return structure.columns().stream()
            .map(column -> {
                Usage columnUsage = usage.getOrDefault(lower(column.columnName()), Usage.NONE);
                return new ColumnUsage(column.columnName(),
                    column.typeName(),
                    column.columnSize(),
                    "YES".equalsIgnoreCase(column.isNullable()),
                    "YES".equalsIgnoreCase(column.isAutoIncrement()),
                    primaryKey.contains(lower(column.columnName())),
                    foreignKey.contains(lower(column.columnName())),
                    column.characterSet(),
                    column.collation(),
                    List.copyOf(columnUsage.roles),
                    columnUsage.queries);
            })
            .toList();
    }

    /** The stat strip: query/execution/time totals and the read/write split. */
    public TableStats getStats(TableReference table) {
        int distinctQueries = 0;
        long totalExecutions = 0;
        double totalTimeMs = 0;
        long readExecutions = 0;
        long writeExecutions = 0;

        for (SqlProfile profile : sqlRepositoryService.getTableQueries(table).toList()) {
            distinctQueries++;
            RuntimeMetrics metrics = profile.getRuntimeMetrics();
            if (metrics == null) {
                continue;
            }
            totalExecutions += metrics.getExecuted();
            totalTimeMs += (metrics.getTotalTime() == null ? 0 : metrics.getTotalTime().doubleValue()) / PICOS_PER_MILLI;
            if (Boolean.TRUE.equals(profile.getIsDml())) {
                writeExecutions += metrics.getExecuted();
            } else {
                readExecutions += metrics.getExecuted();
            }
        }
        return new TableStats(distinctQueries, totalExecutions, totalTimeMs, readExecutions, writeExecutions);
    }

    /** Per-column roles and distinct query counts, in one pass over the table's queries. */
    private Map<String, Usage> collectUsage(TableReference table) {
        Map<String, Usage> usage = new HashMap<>();
        sqlRepositoryService.getTableQueries(table).forEach(profile -> {
            Set<String> countedThisQuery = new HashSet<>();
            for (ColumnReference criteria : profile.getCriteriaColumns()) {
                addUsage(usage, table, criteria, ROLE_CRITERIA, countedThisQuery);
            }
            for (JoinColumns join : profile.getJoinColumns()) {
                addUsage(usage, table, join.getLeft(), ROLE_JOIN, countedThisQuery);
                addUsage(usage, table, join.getRight(), ROLE_JOIN, countedThisQuery);
            }
            for (ColumnReference select : nullSafe(profile.getSelectColumns())) {
                addUsage(usage, table, select, ROLE_SELECT, countedThisQuery);
            }
            for (ColumnReference groupBy : nullSafe(profile.getGroupByColumns())) {
                addUsage(usage, table, groupBy, ROLE_GROUP_BY, countedThisQuery);
            }
        });
        return usage;
    }

    private static void addUsage(Map<String, Usage> usage, TableReference table, ColumnReference column,
                                 String role, Set<String> countedThisQuery) {
        if (column == null || column.getTable() == null || !column.getTable().equalsNormalized(table)) {
            return;
        }
        // analysis keeps raw names (possibly backticked) in column; match on the normalized name
        String name = column.getNormalizedColumn() != null ? column.getNormalizedColumn() : column.getColumn();
        if (name == null) {
            return;
        }
        String key = lower(name);
        Usage columnUsage = usage.computeIfAbsent(key, k -> new Usage());
        columnUsage.roles.add(role);
        if (countedThisQuery.add(key)) {
            columnUsage.queries++;
        }
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static List<ColumnReference> nullSafe(List<ColumnReference> columns) {
        return columns == null ? List.of() : columns;
    }

    public record ColumnUsage(String name, String type, int size, boolean nullable, boolean autoIncrement,
                              boolean primaryKey, boolean foreignKey, String characterSet, String collation,
                              List<String> roles, int queries) { }

    public record TableStats(int distinctQueries, long totalExecutions, double totalTimeMs,
                             long readExecutions, long writeExecutions) { }

    private static final class Usage {
        static final Usage NONE = new Usage();
        private final Set<String> roles = new TreeSet<>();
        private int queries;
    }
}
