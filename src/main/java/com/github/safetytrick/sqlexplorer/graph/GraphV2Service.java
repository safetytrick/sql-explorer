package com.github.safetytrick.sqlexplorer.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryService;
import com.github.safetytrick.sqlexplorer.schema.ForeignKey;
import com.github.safetytrick.sqlexplorer.schema.ForeignKeyDetailService;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaService;
import com.github.safetytrick.sqlexplorer.schema.TableColumn;
import com.github.safetytrick.sqlexplorer.schema.TableIndex;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import org.springframework.stereotype.Service;

/**
 * Graph payload for the canvas view: the schema's tables (plus any table
 * co-queried or FK-referenced alongside them), FK edges labeled with their
 * first column pair, flagged on strict type mismatch and on whether any query
 * joins on them, join edges from analysis, co-query edges weighted by
 * executions, and per-node self-join flags.
 */
@Service
public class GraphV2Service {

    /** Performance Schema times are in picoseconds. */
    private static final double PICOS_PER_MILLI = 1_000_000_000d;

    private final SqlRepositoryService sqlRepositoryService;
    private final SqlSchemaService sqlSchemaService;

    public GraphV2Service(SqlRepositoryService sqlRepositoryService, SqlSchemaService sqlSchemaService) {
        this.sqlRepositoryService = sqlRepositoryService;
        this.sqlSchemaService = sqlSchemaService;
    }

    public GraphV2 getSchemaGraph(String schemaName) {
        return buildGraph(
            structure -> structure.schema() != null && structure.schema().equalsIgnoreCase(schemaName),
            tables -> tables.stream().anyMatch(table -> schemaName.equalsIgnoreCase(table.getNormalizedSchemaName())),
            sqlRepositoryService.getAllAnalyzedSql());
    }

    /** A graph over an arbitrary profile set (tag results, search results): nodes come from the profiles. */
    public GraphV2 getGraphForProfiles(Collection<SqlProfile> profiles) {
        return getGraphForProfiles(profiles, List.of());
    }

    /**
     * A graph over a profile set that also shows every table of these schemas,
     * queried or not, with the foreign keys between them: the workbench graph's
     * "all tables" (sqlx-aoy.16). The profiles still decide the joins, the
     * co-queried pairs and the query counts, so a table no matching query
     * touches is drawn with none.
     */
    public GraphV2 getGraphForProfiles(Collection<SqlProfile> profiles, Collection<String> allTablesOf) {
        Set<String> schemas = new HashSet<>();
        allTablesOf.forEach(schema -> schemas.add(schema.toLowerCase(Locale.ROOT)));
        return buildGraph(
            structure -> structure.schema() != null && schemas.contains(structure.schema().toLowerCase(Locale.ROOT)),
            tables -> true, profiles.stream());
    }

    private GraphV2 buildGraph(Predicate<TableStructure> seed,
                               Predicate<List<TableReference>> profileFilter,
                               Stream<SqlProfile> profiles) {
        Map<String, NodeBuilder> nodes = new TreeMap<>();
        Map<String, CoQueryBuilder> coQueries = new TreeMap<>();
        Map<String, JoinBuilder> joins = new TreeMap<>();
        List<TableStructure> structures = sqlSchemaService.getTables();

        // seed nodes (schema mode: every metadata table of the schema, queried or not)
        for (TableStructure structure : structures) {
            if (seed.test(structure)) {
                node(nodes, structure.schema(), structure.name());
            }
        }

        // one pass over queries: node stats, co-query pairs, self-joins
        profiles.forEach(profile -> {
            List<TableReference> tables = distinctTables(profile);
            if (!profileFilter.test(tables)) {
                return;
            }
            boolean warn = Boolean.TRUE.equals(profile.getAnalysisFailed())
                || (profile.getMismatchedJoins() != null && !profile.getMismatchedJoins().isEmpty());
            RuntimeMetrics metrics = profile.getRuntimeMetrics();
            long executions = metrics == null ? 0 : metrics.getExecuted();
            double timeMs = metrics == null || metrics.getTotalTime() == null
                ? 0 : metrics.getTotalTime().doubleValue() / PICOS_PER_MILLI;
            for (TableReference table : tables) {
                NodeBuilder node = node(nodes, table.getNormalizedSchemaName(), table.getNormalizedTableName());
                node.queryCount++;
                node.warn |= warn;
                node.executions += executions;
                node.queryTimeMs += timeMs;
            }
            for (int i = 0; i < tables.size(); i++) {
                for (int j = i + 1; j < tables.size(); j++) {
                    coQuery(coQueries, tables.get(i), tables.get(j)).add(profile);
                }
            }
            Set<JoinBuilder> joinedHere = new HashSet<>();
            for (JoinColumns join : profile.getJoinColumns()) {
                if (join.getLeft() == null || join.getRight() == null) {
                    continue;
                }
                TableReference left = join.getLeft().getTable();
                TableReference right = join.getRight().getTable();
                if (!named(left) || !named(right)) {
                    continue;
                }
                if (left.equalsNormalized(right)) {
                    continue; // a self-join is a node flag, not an edge: below
                }
                JoinBuilder pair = joinPair(joins, left, right);
                pair.addColumns(join.getLeft(), join.getRight());
                joinedHere.add(pair);
            }
            // a query joining two tables on several columns is still one query
            joinedHere.forEach(pair -> pair.add(profile));
            for (TableReference table : profile.selfJoinedTables()) {
                if (named(table)) {
                    node(nodes, table.getNormalizedSchemaName(), table.getNormalizedTableName()).selfJoin = true;
                }
            }
        });

        // view→base edges: a displayed view pulls its base tables into the graph
        List<GraphV2.ViewEdge> viewEdges = new ArrayList<>();
        for (TableStructure structure : structures) {
            NodeBuilder viewNode = nodes.get(key(structure.schema(), structure.name()));
            if (viewNode == null || !"VIEW".equalsIgnoreCase(structure.type()) || structure.underlyingTables() == null) {
                continue;
            }
            for (TableReference base : structure.underlyingTables()) {
                if (base.getNormalizedSchemaName() == null || base.getNormalizedTableName() == null) {
                    continue;
                }
                NodeBuilder baseNode = node(nodes, base.getNormalizedSchemaName(), base.getNormalizedTableName());
                viewEdges.add(new GraphV2.ViewEdge(viewNode.schema, viewNode.table, baseNode.schema, baseNode.table));
            }
        }

        // decorate every displayed node with schema metadata (column count, view flag)
        for (TableStructure structure : structures) {
            NodeBuilder node = nodes.get(key(structure.schema(), structure.name()));
            if (node == null) {
                continue;
            }
            node.columnCount = structure.columns() == null ? null : structure.columns().size();
            node.view = "VIEW".equalsIgnoreCase(structure.type());
            node.sizeBytes = structure.sizeBytes();
            node.dataBytes = structure.dataBytes();
            node.indexBytes = structure.indexBytes();
        }

        // FK edges between displayed nodes; column type comparison over the loaded structures
        Map<String, Map<String, TableColumn>> columnsByTable = new HashMap<>();
        for (TableStructure structure : structures) {
            columnsByTable.put(key(structure.schema(), structure.name()), columnsOf(structure));
        }
        SchemaFacts facts = new SchemaFacts(structures);
        // declared foreign key column pairs, as "from.column>to.column"
        Set<String> declared = new HashSet<>();
        List<GraphV2.FkEdge> fkEdges = new ArrayList<>();
        for (TableStructure structure : structures) {
            if (!nodes.containsKey(key(structure.schema(), structure.name()))) {
                continue;
            }
            for (ForeignKey fk : structure.foreignKeys()) {
                TableReference target = fk.to().getFirst().getTable();
                if (target == null || !nodes.containsKey(key(target.getNormalizedSchemaName(), target.getNormalizedTableName()))) {
                    continue;
                }
                ColumnReference from = fk.from().getFirst();
                ColumnReference to = fk.to().getFirst();
                boolean mismatch = false;
                for (int i = 0; i < fk.from().size(); i++) {
                    mismatch |= ForeignKeyDetailService.isMismatch(
                        lookup(columnsByTable, fk.from().get(i)),
                        lookup(columnsByTable, fk.to().get(i)));
                }
                String fromKey = key(structure.schema(), structure.name());
                String toKey = key(target.getNormalizedSchemaName(), target.getNormalizedTableName());
                List<String> fromColumns = fk.from().stream().map(GraphV2Service::columnName).toList();
                List<String> toColumns = fk.to().stream().map(GraphV2Service::columnName).toList();
                List<GraphV2.ColumnPair> pairs = new ArrayList<>();
                for (int i = 0; i < fromColumns.size(); i++) {
                    pairs.add(facts.pair(fromKey, fromColumns.get(i), fromColumns, toKey, toColumns.get(i), toColumns, "a"));
                    declared.add(fromKey + "." + fromColumns.get(i) + ">" + toKey + "." + toColumns.get(i));
                    nodes.get(fromKey).keyColumns.add(fromColumns.get(i));
                    nodes.get(toKey).keyColumns.add(toColumns.get(i));
                }
                fkEdges.add(new GraphV2.FkEdge(structure.schema(), structure.name(),
                    target.getNormalizedSchemaName(), target.getNormalizedTableName(),
                    from.getNormalizedColumn() + "→" + to.getNormalizedColumn(),
                    mismatch, joinedOn(joins, structure.schema(), structure.name(), target, fk), pairs));
            }
        }

        List<GraphV2.CoQueryEdge> coQueryEdges = coQueries.values().stream()
            .map(CoQueryBuilder::build)
            .toList();
        List<GraphV2.JoinEdge> joinEdges = joins.values().stream()
            .filter(pair -> nodes.containsKey(pair.aKey) && nodes.containsKey(pair.bKey))
            .map(pair -> {
                pair.pairs.forEach(columns -> {
                    nodes.get(pair.aKey).keyColumns.add(columns.get(0));
                    nodes.get(pair.bKey).keyColumns.add(columns.get(1));
                });
                return pair.build(facts, declared);
            })
            .toList();
        // last, once every drawn relationship has named the columns it attaches to
        List<GraphV2.Node> nodeList = nodes.entrySet().stream()
            .map(entry -> entry.getValue().build(facts.keyColumns(entry.getKey(), entry.getValue().keyColumns)))
            .toList();
        return new GraphV2(nodeList, fkEdges, coQueryEdges, viewEdges, joinEdges);
    }

    private static List<TableReference> distinctTables(SqlProfile profile) {
        Set<String> seen = new HashSet<>();
        List<TableReference> tables = new ArrayList<>();
        for (TableReference table : profile.getTables()) {
            if (table.getNormalizedSchemaName() == null || table.getNormalizedTableName() == null) {
                continue;
            }
            if (seen.add(key(table.getNormalizedSchemaName(), table.getNormalizedTableName()))) {
                tables.add(table);
            }
        }
        return tables;
    }

    private static String key(String schema, String table) {
        return (schema + "." + table).toLowerCase(Locale.ROOT);
    }

    private static NodeBuilder node(Map<String, NodeBuilder> nodes, String schema, String table) {
        return nodes.computeIfAbsent(key(schema, table), k -> new NodeBuilder(schema, table));
    }

    private static CoQueryBuilder coQuery(Map<String, CoQueryBuilder> pairs, TableReference a, TableReference b) {
        String keyA = key(a.getNormalizedSchemaName(), a.getNormalizedTableName());
        String keyB = key(b.getNormalizedSchemaName(), b.getNormalizedTableName());
        String pairKey = keyA.compareTo(keyB) < 0 ? keyA + "|" + keyB : keyB + "|" + keyA;
        return pairs.computeIfAbsent(pairKey, k -> new CoQueryBuilder(a, b));
    }

    private static boolean named(TableReference table) {
        return table != null && table.getNormalizedSchemaName() != null && table.getNormalizedTableName() != null;
    }

    private static JoinBuilder joinPair(Map<String, JoinBuilder> pairs, TableReference left, TableReference right) {
        String keyLeft = key(left.getNormalizedSchemaName(), left.getNormalizedTableName());
        String keyRight = key(right.getNormalizedSchemaName(), right.getNormalizedTableName());
        boolean leftFirst = keyLeft.compareTo(keyRight) < 0;
        String pairKey = leftFirst ? keyLeft + "|" + keyRight : keyRight + "|" + keyLeft;
        return pairs.computeIfAbsent(pairKey, k -> leftFirst ? new JoinBuilder(left, right) : new JoinBuilder(right, left));
    }

    /**
     * Whether queries join the foreign key's tables on every one of its column
     * pairs. Joining the same two tables on other columns does not count: that
     * is a different relationship, and the key would still only be enforcing
     * integrity.
     */
    private static boolean joinedOn(Map<String, JoinBuilder> joins, String schema, String table,
                                    TableReference target, ForeignKey fk) {
        String fromKey = key(schema, table);
        String toKey = key(target.getNormalizedSchemaName(), target.getNormalizedTableName());
        boolean fromFirst = fromKey.compareTo(toKey) < 0;
        JoinBuilder pair = joins.get(fromFirst ? fromKey + "|" + toKey : toKey + "|" + fromKey);
        if (pair == null) {
            return false;
        }
        for (int i = 0; i < fk.from().size(); i++) {
            String fromColumn = columnName(fk.from().get(i));
            String toColumn = columnName(fk.to().get(i));
            if (!pair.columns.contains(fromFirst ? fromColumn + "=" + toColumn : toColumn + "=" + fromColumn)) {
                return false;
            }
        }
        return true;
    }

    private static String columnName(ColumnReference column) {
        String name = column.getNormalizedColumn() != null ? column.getNormalizedColumn() : column.getColumn();
        return name == null ? "?" : name.toLowerCase(Locale.ROOT);
    }

    private static Map<String, TableColumn> columnsOf(TableStructure structure) {
        Map<String, TableColumn> byName = new HashMap<>();
        if (structure.columns() != null) {
            structure.columns().forEach(column -> byName.put(column.columnName().toLowerCase(Locale.ROOT), column));
        }
        return byName;
    }

    private static TableColumn lookup(Map<String, Map<String, TableColumn>> columnsByTable, ColumnReference reference) {
        if (reference.getTable() == null) {
            return null;
        }
        Map<String, TableColumn> columns = columnsByTable.get(
            key(reference.getTable().getNormalizedSchemaName(), reference.getTable().getNormalizedTableName()));
        if (columns == null) {
            return null;
        }
        String name = reference.getNormalizedColumn() != null ? reference.getNormalizedColumn() : reference.getColumn();
        return name == null ? null : columns.get(name.toLowerCase(Locale.ROOT));
    }

    private static final class NodeBuilder {
        private final String schema;
        private final String table;
        private Integer columnCount;
        private int queryCount;
        private boolean warn;
        private boolean selfJoin;
        private boolean view;
        private long executions;
        private double queryTimeMs;
        private Long sizeBytes;
        private Long dataBytes;
        private Long indexBytes;
        /** The columns a drawn relationship attaches to, lower-cased. */
        private final Set<String> keyColumns = new TreeSet<>();

        private NodeBuilder(String schema, String table) {
            this.schema = schema;
            this.table = table;
        }

        private GraphV2.Node build(List<GraphV2.KeyColumn> columns) {
            return new GraphV2.Node(schema, table, columnCount, queryCount, warn, selfJoin, view, columns,
                executions, queryTimeMs, sizeBytes, dataBytes, indexBytes);
        }
    }

    /**
     * What the schema says about columns, for an ERD: which are unique, which
     * nullable, which start a foreign key. Everything is keyed by the
     * lower-cased {@link #key table key} and column name.
     */
    private static final class SchemaFacts {
        private final Map<String, TableStructure> tables = new HashMap<>();
        private final Map<String, List<Set<String>>> uniqueIndexes = new HashMap<>();
        private final Map<String, Set<String>> primary = new HashMap<>();
        private final Map<String, Set<String>> foreignKeyColumns = new HashMap<>();

        private SchemaFacts(List<TableStructure> structures) {
            for (TableStructure structure : structures) {
                String table = key(structure.schema(), structure.name());
                tables.put(table, structure);
                List<Set<String>> unique = new ArrayList<>();
                if (structure.indices() != null) {
                    for (TableIndex index : structure.indices()) {
                        if (!index.unique() || index.columns() == null || index.columns().isEmpty()) {
                            continue;
                        }
                        Set<String> columns = new HashSet<>();
                        index.columns().forEach(column -> columns.add(column.toLowerCase(Locale.ROOT)));
                        unique.add(columns);
                        if ("PRIMARY".equalsIgnoreCase(index.name())) {
                            primary.put(table, columns);
                        }
                    }
                }
                uniqueIndexes.put(table, unique);
                Set<String> starts = new HashSet<>();
                if (structure.foreignKeys() != null) {
                    structure.foreignKeys().forEach(fk -> fk.from().forEach(column -> starts.add(columnName(column))));
                }
                foreignKeyColumns.put(table, starts);
            }
        }

        /** Whether {@code joined} covers a unique index that includes {@code column}; false for an unknown table. */
        private boolean unique(String table, String column, Collection<String> joined) {
            return uniqueIndexes.getOrDefault(table, List.of()).stream()
                .anyMatch(index -> index.contains(column) && joined.containsAll(index));
        }

        private boolean nullable(String table, String column) {
            TableColumn found = column(table, column);
            return found != null && "YES".equalsIgnoreCase(found.isNullable());
        }

        private TableColumn column(String table, String column) {
            TableStructure structure = tables.get(table);
            if (structure == null || structure.columns() == null) {
                return null;
            }
            return structure.columns().stream()
                .filter(candidate -> candidate.columnName().equalsIgnoreCase(column))
                .findFirst().orElse(null);
        }

        private GraphV2.ColumnPair pair(String aTable, String a, Collection<String> aJoined,
                                        String bTable, String b, Collection<String> bJoined, String declared) {
            // nothing is known of a table whose structure was never read, which is not the same as "not unique"
            boolean aKnown = tables.containsKey(aTable);
            boolean bKnown = tables.containsKey(bTable);
            return new GraphV2.ColumnPair(a, b,
                aKnown ? unique(aTable, a, aJoined) : null, bKnown ? unique(bTable, b, bJoined) : null,
                aKnown ? nullable(aTable, a) : null, bKnown ? nullable(bTable, b) : null, declared);
        }

        /**
         * A card's rows: the primary key, the foreign keys and the columns
         * relationships attach to, in table order. A table whose structure is
         * not known still lists the columns it is joined on, so its lines have
         * rows to end at.
         */
        private List<GraphV2.KeyColumn> keyColumns(String table, Set<String> attached) {
            TableStructure structure = tables.get(table);
            if (structure == null || structure.columns() == null || structure.columns().isEmpty()) {
                return attached.stream()
                    .map(name -> new GraphV2.KeyColumn(name, null, false, false, false, false))
                    .toList();
            }
            Set<String> keys = primary.getOrDefault(table, Set.of());
            Set<String> starts = foreignKeyColumns.getOrDefault(table, Set.of());
            return structure.columns().stream()
                .sorted(Comparator.comparingInt(TableColumn::ordinal))
                .filter(column -> {
                    String name = column.columnName().toLowerCase(Locale.ROOT);
                    return keys.contains(name) || starts.contains(name) || attached.contains(name);
                })
                .map(column -> {
                    String name = column.columnName().toLowerCase(Locale.ROOT);
                    return new GraphV2.KeyColumn(column.columnName(), column.typeName(),
                        "YES".equalsIgnoreCase(column.isNullable()), keys.contains(name),
                        unique(table, name, Set.of(name)), starts.contains(name));
                })
                .toList();
        }
    }

    private static final class JoinBuilder {
        private final TableReference a;
        private final TableReference b;
        private final String aKey;
        private final String bKey;
        private final Set<String> columns = new TreeSet<>();
        /** The same pairs as {@code columns}, as [a column, b column], in the same order. */
        private final Map<String, List<String>> pairsByLabel = new TreeMap<>();
        private final Collection<List<String>> pairs = pairsByLabel.values();
        private int queries;
        private long executions;

        private JoinBuilder(TableReference a, TableReference b) {
            this.a = a;
            this.b = b;
            this.aKey = key(a.getNormalizedSchemaName(), a.getNormalizedTableName());
            this.bKey = key(b.getNormalizedSchemaName(), b.getNormalizedTableName());
        }

        /** Records the column pair oriented a=b, whichever side of the ON it was written on. */
        private void addColumns(ColumnReference left, ColumnReference right) {
            boolean leftIsA = key(left.getTable().getNormalizedSchemaName(), left.getTable().getNormalizedTableName())
                .equals(aKey);
            String aColumn = columnName(leftIsA ? left : right);
            String bColumn = columnName(leftIsA ? right : left);
            columns.add(aColumn + "=" + bColumn);
            pairsByLabel.put(aColumn + "=" + bColumn, List.of(aColumn, bColumn));
        }

        private void add(SqlProfile profile) {
            queries++;
            if (profile.getRuntimeMetrics() != null) {
                executions += profile.getRuntimeMetrics().getExecuted();
            }
        }

        private GraphV2.JoinEdge build(SchemaFacts facts, Set<String> declared) {
            List<String> aJoined = pairs.stream().map(List::getFirst).toList();
            List<String> bJoined = pairs.stream().map(List::getLast).toList();
            List<GraphV2.ColumnPair> built = pairs.stream().map(pair -> {
                String aColumn = pair.getFirst();
                String bColumn = pair.getLast();
                String side = declared.contains(aKey + "." + aColumn + ">" + bKey + "." + bColumn) ? "a"
                    : declared.contains(bKey + "." + bColumn + ">" + aKey + "." + aColumn) ? "b" : null;
                return facts.pair(aKey, aColumn, aJoined, bKey, bColumn, bJoined, side);
            }).toList();
            return new GraphV2.JoinEdge(a.getNormalizedSchemaName(), a.getNormalizedTableName(),
                b.getNormalizedSchemaName(), b.getNormalizedTableName(), List.copyOf(columns), queries, executions, built);
        }
    }

    private static final class CoQueryBuilder {
        private final TableReference a;
        private final TableReference b;
        private int queries;
        private long executions;

        private CoQueryBuilder(TableReference a, TableReference b) {
            this.a = a;
            this.b = b;
        }

        private void add(SqlProfile profile) {
            queries++;
            if (profile.getRuntimeMetrics() != null) {
                executions += profile.getRuntimeMetrics().getExecuted();
            }
        }

        private GraphV2.CoQueryEdge build() {
            return new GraphV2.CoQueryEdge(a.getNormalizedSchemaName(), a.getNormalizedTableName(),
                b.getNormalizedSchemaName(), b.getNormalizedTableName(), queries, executions);
        }
    }
}
