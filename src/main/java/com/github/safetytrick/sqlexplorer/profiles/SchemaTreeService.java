package com.github.safetytrick.sqlexplorer.profiles;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.github.safetytrick.sqlexplorer.analysis.IgnoredSchemas;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.evidence.ArchivedActivity;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryService;
import com.github.safetytrick.sqlexplorer.profiles.SchemaTree.SchemaNode;
import com.github.safetytrick.sqlexplorer.profiles.SchemaTree.TableNode;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaService;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import org.springframework.stereotype.Service;

@Service
public class SchemaTreeService {

    private final SqlRepositoryService sqlRepositoryService;
    private final SqlSchemaService sqlSchemaService;
    private final IgnoredSchemas ignoredSchemas;
    private final ArchivedActivity archivedActivity;

    public SchemaTreeService(SqlRepositoryService sqlRepositoryService,
                             SqlSchemaService sqlSchemaService,
                             IgnoredSchemas ignoredSchemas,
                             ArchivedActivity archivedActivity) {
        this.sqlRepositoryService = sqlRepositoryService;
        this.sqlSchemaService = sqlSchemaService;
        this.ignoredSchemas = ignoredSchemas;
        this.archivedActivity = archivedActivity;
    }

    public SchemaTree getTree() {
        Map<String, Map<String, TableStats>> stats = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        sqlRepositoryService.getAllAnalyzedSql().forEach(profile -> {
            boolean warn = hasAnalyzerFinding(profile);
            Set<String> seen = new HashSet<>();
            for (TableReference table : profile.getTables()) {
                if (table.getNormalizedSchemaName() == null || table.getNormalizedTableName() == null) {
                    continue;
                }
                if (seen.add((table.getNormalizedSchemaName() + "." + table.getNormalizedTableName())
                                 .toLowerCase(Locale.ROOT))) {
                    stats.computeIfAbsent(table.getNormalizedSchemaName(), s -> newTableMap())
                        .computeIfAbsent(table.getNormalizedTableName(), t -> new TableStats())
                        .addQuery(warn);
                }
            }
        });

        for (TableStructure structure : sqlSchemaService.getTables()) {
            if (structure.schema() == null) {
                continue;
            }
            TableStats tableStats = stats.computeIfAbsent(structure.schema(), s -> newTableMap())
                .computeIfAbsent(structure.name(), t -> new TableStats());
            tableStats.columnCount = structure.columns() == null ? null : structure.columns().size();
            tableStats.view = "VIEW".equalsIgnoreCase(structure.type());
            tableStats.inSchema = true;
        }

        Map<String, java.time.LocalDateTime> archived = archivedActivity.lastTouchedBeforeThisRun();

        List<SchemaNode> schemas = stats.entrySet().stream()
            .map(schema -> new SchemaNode(schema.getKey(), schema.getValue().entrySet().stream()
                .map(table -> new TableNode(table.getKey(),
                    table.getValue().columnCount,
                    table.getValue().queryCount,
                    table.getValue().warn,
                    table.getValue().view,
                    table.getValue().inSchema,
                    table.getValue().queryCount > 0 ? null
                        : archived.get((schema.getKey() + "." + table.getKey()).toLowerCase(Locale.ROOT))))
                .toList()))
            .sorted(Comparator
                        .comparing((SchemaNode node) -> ignoredSchemas.contains(node.name()))
                        .thenComparing(node -> node.name().toLowerCase(Locale.ROOT)))
            .toList();
        return new SchemaTree(schemas);
    }

    private static Map<String, TableStats> newTableMap() {
        return new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    }

    private static boolean hasAnalyzerFinding(SqlProfile profile) {
        return Boolean.TRUE.equals(profile.getAnalysisFailed())
            || (profile.getMismatchedJoins() != null && !profile.getMismatchedJoins().isEmpty());
    }

    private static final class TableStats {
        private Integer columnCount;
        private int queryCount;
        private boolean warn;
        private boolean view;
        private boolean inSchema;

        void addQuery(boolean warned) {
            queryCount++;
            warn |= warned;
        }
    }
}
