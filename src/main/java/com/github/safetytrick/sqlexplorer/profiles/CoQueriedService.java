package com.github.safetytrick.sqlexplorer.profiles;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryService;
import com.github.safetytrick.sqlexplorer.schema.ForeignKey;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaService;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import org.springframework.stereotype.Service;

@Service
public class CoQueriedService {

    private static final double PICOS_PER_MILLI = 1_000_000_000d;

    private final SqlRepositoryService sqlRepositoryService;
    private final SqlSchemaService sqlSchemaService;

    public CoQueriedService(SqlRepositoryService sqlRepositoryService, SqlSchemaService sqlSchemaService) {
        this.sqlRepositoryService = sqlRepositoryService;
        this.sqlSchemaService = sqlSchemaService;
    }

    public List<CoQueriedTable> getCoQueried(TableReference table) {
        Map<String, Accumulator> partners = new TreeMap<>();

        sqlRepositoryService.getTableQueries(table).forEach(profile -> {
            Set<String> seen = new HashSet<>();
            for (TableReference other : profile.getTables()) {
                if (other.getNormalizedTableName() == null || other.equalsNormalized(table)) {
                    continue;
                }
                if (!seen.add(key(other))) {
                    continue;
                }
                Accumulator accumulator = partners.computeIfAbsent(key(other), k -> new Accumulator(other));
                accumulator.add(profile.getRuntimeMetrics(), isJoined(profile, table, other));
                accumulator.addJoinTypes(profile, table, other);
            }
        });

        return partners.values().stream()
            .map(accumulator -> accumulator.toResult(fkDirection(table, accumulator.table)))
            .sorted(Comparator.comparingLong(CoQueriedTable::executions).reversed())
            .toList();
    }

    private static String key(TableReference table) {
        return (table.getNormalizedSchemaName() + "." + table.getNormalizedTableName()).toLowerCase(Locale.ROOT);
    }

    private static boolean isJoined(SqlProfile profile, TableReference a, TableReference b) {
        for (JoinColumns join : profile.getJoinColumns()) {
            if (pairs(join, a, b)) {
                return true;
            }
        }
        return false;
    }

    private static boolean pairs(JoinColumns join, TableReference a, TableReference b) {
        if (join.getLeft() == null || join.getRight() == null) {
            return false;
        }
        TableReference left = join.getLeft().getTable();
        TableReference right = join.getRight().getTable();
        if (left == null || right == null) {
            return false;
        }
        return (left.equalsNormalized(a) && right.equalsNormalized(b))
            || (left.equalsNormalized(b) && right.equalsNormalized(a));
    }

    private String fkDirection(TableReference table, TableReference other) {
        if (referencesViaForeignKey(sqlSchemaService.getTable(table), other)) {
            return "out";
        }
        if (referencesViaForeignKey(sqlSchemaService.getTable(other), table)) {
            return "in";
        }
        return null;
    }

    private static boolean referencesViaForeignKey(TableStructure structure, TableReference target) {
        if (structure == null || structure.foreignKeys() == null) {
            return false;
        }
        return structure.foreignKeys().stream()
            .flatMap(fk -> fk.to().stream())
            .anyMatch(column -> column.getTable() != null && column.getTable().equalsNormalized(target));
    }

    public record CoQueriedTable(String schema, String table, int queries, long executions,
                                 Double avgTimeMs, boolean joined, List<String> joinTypes, String fkDirection) { }

    private static final class Accumulator {
        private final TableReference table;
        private int queries;
        private long executions;
        private double totalTimePicos;
        private boolean joined;
        private final Set<String> joinTypes = new TreeSet<>();

        private Accumulator(TableReference table) {
            this.table = table;
        }

        void add(RuntimeMetrics metrics, boolean joinedInThisQuery) {
            queries++;
            joined |= joinedInThisQuery;
            if (metrics != null) {
                executions += metrics.getExecuted();
                totalTimePicos += metrics.getTotalTime() == null ? 0 : metrics.getTotalTime().doubleValue();
            }
        }

        void addJoinTypes(SqlProfile profile, TableReference a, TableReference b) {
            for (JoinColumns join : profile.getJoinColumns()) {
                if (join.getJoinType() != null && pairs(join, a, b)) {
                    joinTypes.add(join.getJoinType());
                }
            }
        }

        CoQueriedTable toResult(String fkDirection) {
            Double avgTimeMs = executions == 0 ? null : totalTimePicos / executions / PICOS_PER_MILLI;
            return new CoQueriedTable(table.getNormalizedSchemaName(), table.getNormalizedTableName(),
                queries, executions, avgTimeMs, joined, List.copyOf(joinTypes), fkDirection);
        }
    }
}
