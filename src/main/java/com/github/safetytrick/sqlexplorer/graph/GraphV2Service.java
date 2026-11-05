package com.github.safetytrick.sqlexplorer.graph;

import java.util.ArrayList;
import java.util.Collection;
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
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryService;
import com.github.safetytrick.sqlexplorer.schema.ForeignKey;
import com.github.safetytrick.sqlexplorer.schema.ForeignKeyDetailService;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaService;
import com.github.safetytrick.sqlexplorer.schema.TableColumn;
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
            for (TableReference table : tables) {
                NodeBuilder node = node(nodes, table.getNormalizedSchemaName(), table.getNormalizedTableName());
                node.queryCount++;
                node.warn |= warn;
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
                    node(nodes, left.getNormalizedSchemaName(), left.getNormalizedTableName()).selfJoin = true;
                    continue;
                }
                JoinBuilder pair = joinPair(joins, left, right);
                pair.addColumns(join.getLeft(), join.getRight());
                joinedHere.add(pair);
            }
            // a query joining two tables on several columns is still one query
            joinedHere.forEach(pair -> pair.add(profile));
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
        }

        // FK edges between displayed nodes; column type comparison over the loaded structures
        Map<String, Map<String, TableColumn>> columnsByTable = new HashMap<>();
        for (TableStructure structure : structures) {
            columnsByTable.put(key(structure.schema(), structure.name()), columnsOf(structure));
        }
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
                fkEdges.add(new GraphV2.FkEdge(structure.schema(), structure.name(),
                    target.getNormalizedSchemaName(), target.getNormalizedTableName(),
                    from.getNormalizedColumn() + "→" + to.getNormalizedColumn(),
                    mismatch, joinedOn(joins, structure.schema(), structure.name(), target, fk)));
            }
        }

        List<GraphV2.Node> nodeList = nodes.values().stream()
            .map(NodeBuilder::build)
            .toList();
        List<GraphV2.CoQueryEdge> coQueryEdges = coQueries.values().stream()
            .map(CoQueryBuilder::build)
            .toList();
        List<GraphV2.JoinEdge> joinEdges = joins.values().stream()
            .filter(pair -> nodes.containsKey(pair.aKey) && nodes.containsKey(pair.bKey))
            .map(JoinBuilder::build)
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

        private NodeBuilder(String schema, String table) {
            this.schema = schema;
            this.table = table;
        }

        private GraphV2.Node build() {
            return new GraphV2.Node(schema, table, columnCount, queryCount, warn, selfJoin, view);
        }
    }

    private static final class JoinBuilder {
        private final TableReference a;
        private final TableReference b;
        private final String aKey;
        private final String bKey;
        private final Set<String> columns = new TreeSet<>();
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
            columns.add(leftIsA ? columnName(left) + "=" + columnName(right) : columnName(right) + "=" + columnName(left));
        }

        private void add(SqlProfile profile) {
            queries++;
            if (profile.getRuntimeMetrics() != null) {
                executions += profile.getRuntimeMetrics().getExecuted();
            }
        }

        private GraphV2.JoinEdge build() {
            return new GraphV2.JoinEdge(a.getNormalizedSchemaName(), a.getNormalizedTableName(),
                b.getNormalizedSchemaName(), b.getNormalizedTableName(), List.copyOf(columns), queries, executions);
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
