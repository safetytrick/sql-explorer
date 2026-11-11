package com.github.safetytrick.sqlexplorer.profiles;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.CriteriaComparison;
import com.github.safetytrick.sqlexplorer.avro.IndexHint;
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
    static final String ROLE_ORDER_BY = "order by";

    private static final String KIND_LIKE = "like";
    private static final String KIND_NOT_LIKE = "not like";

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
                    columnUsage.queries,
                    Map.copyOf(columnUsage.comparisons),
                    columnUsage.likeQueries == 0 ? null : new LikeUsage(columnUsage.likeQueries,
                        columnUsage.leadingWildcardQueries, columnUsage.unknownWildcardQueries));
            })
            .toList();
    }

    /**
     * Indexes of the table with how queries hint them, the index counterpart
     * of {@link #getColumns}: each hint action that names an index, and the
     * distinct queries naming it (sqlx-yj0).
     *
     * <p>An index a query hints that the table does not have follows the
     * table's own, flagged {@code exists = false}. MySQL rejects such a
     * statement (error 1176), so those are queries that fail — whether
     * against an index since dropped, or another server's schema.
     */
    public List<IndexUsage> getIndexes(TableReference table) {
        TableStructure structure = sqlSchemaService.getTable(table);
        if (structure == null || structure.indices() == null) {
            return List.of();
        }

        Map<String, Usage> usage = collectHintUsage(table);
        Map<String, IndexUsage> indexes = new LinkedHashMap<>();
        for (TableIndex index : structure.indices()) {
            String key = lower(index.name());
            Usage indexUsage = usage.getOrDefault(key, Usage.NONE);
            indexes.putIfAbsent(key, new IndexUsage(index.name(),
                PRIMARY_INDEX.equalsIgnoreCase(index.name()),
                index.unique(),
                index.columns(),
                true,
                List.copyOf(indexUsage.roles),
                indexUsage.queries));
        }
        usage.entrySet().stream()
            .filter(entry -> !indexes.containsKey(entry.getKey()))
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> indexes.put(entry.getKey(), new IndexUsage(entry.getValue().name,
                false, false, List.of(), false, List.copyOf(entry.getValue().roles), entry.getValue().queries)));
        return List.copyOf(indexes.values());
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
            for (ColumnReference orderBy : nullSafe(profile.getOrderByColumns())) {
                addUsage(usage, table, orderBy, ROLE_ORDER_BY, countedThisQuery);
            }
            addComparisons(usage, table, profile.getCriteriaComparisons());
        });
        return usage;
    }

    /**
     * Qualifies the criteria role with how one query compares each column:
     * per kind, and for LIKE whether the pattern's leading wildcard is known
     * (sqlx-n5j.7). Every count is of distinct queries.
     */
    private static void addComparisons(Map<String, Usage> usage, TableReference table,
                                       List<CriteriaComparison> comparisons) {
        if (comparisons == null) {
            return;
        }
        Map<String, Set<String>> kindsThisQuery = new HashMap<>();
        Set<String> likeThisQuery = new HashSet<>();
        Set<String> leadingThisQuery = new HashSet<>();
        Set<String> unknownThisQuery = new HashSet<>();
        for (CriteriaComparison comparison : comparisons) {
            String key = columnKey(table, comparison.getColumn());
            if (key == null) {
                continue;
            }
            Usage columnUsage = usage.computeIfAbsent(key, k -> new Usage());
            if (kindsThisQuery.computeIfAbsent(key, k -> new HashSet<>()).add(comparison.getKind())) {
                columnUsage.comparisons.merge(comparison.getKind(), 1, Integer::sum);
            }
            if (KIND_LIKE.equals(comparison.getKind()) || KIND_NOT_LIKE.equals(comparison.getKind())) {
                if (likeThisQuery.add(key)) {
                    columnUsage.likeQueries++;
                }
                if (Boolean.TRUE.equals(comparison.getLeadingWildcard()) && leadingThisQuery.add(key)) {
                    columnUsage.leadingWildcardQueries++;
                }
                if (comparison.getLeadingWildcard() == null && unknownThisQuery.add(key)) {
                    columnUsage.unknownWildcardQueries++;
                }
            }
        }
    }

    /** Per-index hint actions and distinct query counts, in one pass over the table's queries. */
    private Map<String, Usage> collectHintUsage(TableReference table) {
        Map<String, Usage> usage = new HashMap<>();
        sqlRepositoryService.getTableQueries(table).forEach(profile -> {
            Set<String> countedThisQuery = new HashSet<>();
            for (IndexHint hint : profile.getIndexHints() == null ? List.<IndexHint>of() : profile.getIndexHints()) {
                if (hint.getTable() == null || !hint.getTable().equalsNormalized(table)) {
                    continue;
                }
                String key = lower(hint.getNormalizedIndex());
                Usage indexUsage = usage.computeIfAbsent(key, k -> new Usage());
                if (indexUsage.name == null) {
                    indexUsage.name = hint.getNormalizedIndex();
                }
                indexUsage.roles.add(lower(hint.getAction()));
                if (countedThisQuery.add(key)) {
                    indexUsage.queries++;
                }
            }
        });
        return usage;
    }

    private static void addUsage(Map<String, Usage> usage, TableReference table, ColumnReference column,
                                 String role, Set<String> countedThisQuery) {
        String key = columnKey(table, column);
        if (key == null) {
            return;
        }
        Usage columnUsage = usage.computeIfAbsent(key, k -> new Usage());
        columnUsage.roles.add(role);
        if (countedThisQuery.add(key)) {
            columnUsage.queries++;
        }
    }

    /** The usage key of a column of {@code table}; null for another table's column. */
    private static String columnKey(TableReference table, ColumnReference column) {
        if (column == null || column.getTable() == null || !column.getTable().equalsNormalized(table)) {
            return null;
        }
        // analysis keeps raw names (possibly backticked) in column; match on the normalized name
        String name = column.getNormalizedColumn() != null ? column.getNormalizedColumn() : column.getColumn();
        return name == null ? null : lower(name);
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static List<ColumnReference> nullSafe(List<ColumnReference> columns) {
        return columns == null ? List.of() : columns;
    }

    /**
     * One column and how queries use it. {@code comparisons} qualifies the
     * criteria role: per comparison kind (equality, range, in, like, ...), the
     * distinct queries comparing the column that way. {@code like} is null
     * unless some query pattern-matches on it.
     */
    public record ColumnUsage(String name, String type, int size, boolean nullable, boolean autoIncrement,
                              boolean primaryKey, boolean foreignKey, String characterSet, String collation,
                              List<String> roles, int queries, Map<String, Integer> comparisons,
                              LikeUsage like) { }

    /**
     * The criteria role's LIKE qualifier (sqlx-n5j.7): distinct queries
     * matching the column with LIKE or NOT LIKE, those whose pattern is known
     * to start with a wildcard (no index prefix can serve them), and those
     * whose pattern could not be read — usually a {@code ?} parameter.
     */
    public record LikeUsage(int queries, int leadingWildcard, int unknownWildcard) { }

    /**
     * One index and how queries hint it. {@code hints} holds the actions —
     * {@code use}, {@code force}, {@code ignore} — and {@code queries} the
     * distinct queries naming it in any of them.
     */
    public record IndexUsage(String name, boolean primary, boolean unique, List<String> columns,
                             boolean exists, List<String> hints, int queries) { }

    public record TableStats(int distinctQueries, long totalExecutions, double totalTimeMs,
                             long readExecutions, long writeExecutions) { }

    private static final class Usage {
        static final Usage NONE = new Usage();
        private final Set<String> roles = new TreeSet<>();
        private int queries;
        private final Map<String, Integer> comparisons = new TreeMap<>();
        private int likeQueries;
        private int leadingWildcardQueries;
        private int unknownWildcardQueries;
        /** The first spelling seen, for an index the schema does not name. */
        private String name;
    }
}
