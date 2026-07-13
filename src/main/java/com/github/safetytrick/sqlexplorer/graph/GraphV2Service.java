package com.github.safetytrick.sqlexplorer.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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
 * first column pair and flagged on strict type mismatch, co-query edges
 * weighted by executions, and per-node self-join flags.
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
        Map<String, NodeBuilder> nodes = new TreeMap<>();
        Map<String, CoQueryBuilder> coQueries = new TreeMap<>();
        List<TableStructure> structures = sqlSchemaService.getTables();

        // metadata tables of the schema
        for (TableStructure structure : structures) {
            if (structure.schema() != null && structure.schema().equalsIgnoreCase(schemaName)) {
                node(nodes, structure.schema(), structure.name())
                    .columnCount = structure.columns() == null ? null : structure.columns().size();
            }
        }

        // one pass over queries: node stats, co-query pairs, self-joins
        sqlRepositoryService.getAllAnalyzedSql().forEach(profile -> {
            List<TableReference> tables = distinctTables(profile);
            boolean touchesSchema = tables.stream()
                .anyMatch(table -> schemaName.equalsIgnoreCase(table.getNormalizedSchemaName()));
            if (!touchesSchema) {
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
            for (JoinColumns join : profile.getJoinColumns()) {
                if (join.getLeft() == null || join.getRight() == null) {
                    continue;
                }
                TableReference left = join.getLeft().getTable();
                TableReference right = join.getRight().getTable();
                if (left != null && right != null && left.equalsNormalized(right)
                    && left.getNormalizedSchemaName() != null && left.getNormalizedTableName() != null) {
                    node(nodes, left.getNormalizedSchemaName(), left.getNormalizedTableName()).selfJoin = true;
                }
            }
        });

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
                    mismatch));
            }
        }

        List<GraphV2.Node> nodeList = nodes.values().stream()
            .map(NodeBuilder::build)
            .toList();
        List<GraphV2.CoQueryEdge> coQueryEdges = coQueries.values().stream()
            .map(CoQueryBuilder::build)
            .toList();
        return new GraphV2(nodeList, fkEdges, coQueryEdges);
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

        private NodeBuilder(String schema, String table) {
            this.schema = schema;
            this.table = table;
        }

        private GraphV2.Node build() {
            return new GraphV2.Node(schema, table, columnCount, queryCount, warn, selfJoin);
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
