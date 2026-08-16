package extensions.com.github.safetytrick.sqlexplorer.avro.SqlProfile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.google.common.hash.Hashing;
import extensions.com.github.safetytrick.sqlexplorer.avro.ColumnReference.ColumnReferenceExtension;
import extensions.com.github.safetytrick.sqlexplorer.avro.JoinColumns.JoinColumnsExtension;
import manifold.ext.rt.api.Extension;
import manifold.ext.rt.api.This;

@Extension
public class SqlProfileExtension {

    public static final Comparator<SqlProfile> comparator = (thiz, other) -> {
        if (thiz == other) {
            return 0;
        }
        if (thiz == null) {
            return -1;
        } else if  (other == null) {
            return 1;
        }
        int result = thiz.getQueryActivityStatus().compareTo(other.getQueryActivityStatus());
        if (result != 0) {
            return result;
        }

        if (thiz.getRuntimeMetrics() == null) {
            return other.getRuntimeMetrics() == null ? 0 : -1;
        } else if (other.getRuntimeMetrics() == null) {
            return 1;
        }

        // reversed
        return other.getRuntimeMetrics().getTotalTime().compareTo(thiz.getRuntimeMetrics().getTotalTime());
    };

    public static boolean constrainedBy(@This SqlProfile profile, ColumnReference reference) {
        if (profile.getJoinColumns().stream().anyMatch(join ->
            JoinColumnsExtension.contains(join, reference))) {
            return true;
        }
        return profile.getCriteriaColumns().stream().anyMatch(
            column -> ColumnReferenceExtension.comparator.compare(column, reference) == 0
        );
    }

    public static List<String> getPerfDigests(@This SqlProfile thiz) {
        if (thiz.getRuntimeMetrics() != null && thiz.getRuntimeMetrics().getDigest() != null) {
            return Collections.singletonList(thiz.getRuntimeMetrics().getDigest());
        }
        return Collections.emptyList();
    }

    public static List<String> getAllIds(@This SqlProfile thiz) {
        var result = new ArrayList<String>(3);
        if (thiz.getNormalizedDigest() != null) {
            result.add(thiz.getNormalizedDigest());
        }
        if (thiz.getDigest() != null) {
            result.add(thiz.getDigest());
        }
        result.addAll(thiz.getPerfDigests());
        result.add(thiz.getId());

        return result;
    }

    public static class Builder {
        public static SqlProfile.Builder joinPair(@This SqlProfile.Builder thiz, String leftColumn, String rightColumn) {
            leftColumn = transformName(leftColumn);
            rightColumn = transformName(rightColumn);
            JoinColumns joinPair = JoinColumnsExtension.fromReferences(leftColumn, rightColumn);

            if (thiz.getJoinColumns() == null) {
                thiz.setJoinColumns(new ArrayList<>());
                thiz.getJoinColumns().add(joinPair);
            } else if (thiz.getJoinColumns().stream().noneMatch(joinPair::equalsNormalized)) {
                thiz.getJoinColumns().add(joinPair);
            }
            return thiz;
        }

        private static String transformName(String name) {
            return name.replace("`", "");
        }

        public static SqlProfile.Builder criteriaColumn(@This SqlProfile.Builder thiz, ColumnReference column) {
            if (thiz.getCriteriaColumns() == null) {
              thiz.setCriteriaColumns(new ArrayList<>());
              thiz.getCriteriaColumns().add(column);
            } else if (thiz.getCriteriaColumns().stream().noneMatch(column::equalsNormalized)) {
                thiz.getCriteriaColumns().add(column);
            }
            return thiz;
        }

        public static SqlProfile.Builder selectColumn(@This SqlProfile.Builder thiz, ColumnReference column) {
            if (thiz.getSelectColumns() == null) {
                thiz.setSelectColumns(new ArrayList<>());
                thiz.getSelectColumns().add(column);
            } else if (thiz.getSelectColumns().stream().noneMatch(column::equalsNormalized)) {
                thiz.getSelectColumns().add(column);
            }
            return thiz;
        }

        public static SqlProfile.Builder groupByColumn(@This SqlProfile.Builder thiz, ColumnReference column) {
            if (thiz.getGroupByColumns() == null) {
                thiz.setGroupByColumns(new ArrayList<>());
                thiz.getGroupByColumns().add(column);
            } else if (thiz.getGroupByColumns().stream().noneMatch(column::equalsNormalized)) {
                thiz.getGroupByColumns().add(column);
            }
            return thiz;
        }

        public static void addSchema(@This SqlProfile.Builder thiz, String schema) {
            if (schema == null) {
                return;
            }
            if (thiz.getSchemas() == null) {
                thiz.setSchemas(new ArrayList<>());
                thiz.getSchemas().add(schema);
            } else if (!thiz.getSchemas().contains(schema)) {
                thiz.getSchemas().add(schema);
            }
        }

        private static String calculateQueryDigest(String query) {
            return Hashing.sha256()
                .hashString(query, StandardCharsets.UTF_8)
                .toString();
        }

        public static SqlProfile.Builder complete(@This SqlProfile.Builder thiz, String defaultSchema) {
            emptyUnsetLists(thiz);
            if (thiz.getSchemaName() == null) {
                thiz.setSchemaName(defaultSchema);
            }
            if (thiz.getTables() != null) {
                for (TableReference table : thiz.getTables()) {
                    addSchema(thiz, table.getNormalizedSchemaName());
                }
            }

            // The schemas list is derived from the tables for every profile,
            // analysed or not; the flag was only ever set by SchemaAnalyzer,
            // which does not run when analysis is skipped. That left a skipped
            // cross-schema statement listing two schemas while claiming one.
            // Deriving it from the same data keeps the pair honest. This only
            // ever promotes false to true, so it cannot contradict the analyzer,
            // which sets true in exactly the cases where the list has >1 entry.
            if (thiz.getSchemas() != null && thiz.getSchemas().size() > 1) {
                thiz.setIsMultiSchema(true);
            }

            if (thiz.getAnalysisFailureReason() != null) {
                thiz.setAnalysisFailed(true);
            }

            if (thiz.getRuntimeMetrics() != null) {
                thiz.setDigest(thiz.getRuntimeMetrics().getDigest());
            }

            if (thiz.getNormalizedDigest() != null) {
                thiz.setDigest(thiz.getNormalizedDigest());
            } else if (thiz.getDigest() != null) {
                thiz.setNormalizedDigest(thiz.getDigest());
            } else {
                String queryDigest = calculateQueryDigest(thiz.getQuery());
                thiz.setNormalizedDigest(queryDigest);
                thiz.setDigest(queryDigest);
            }

            if (thiz.getId() == null && thiz.getDigest() != null) {
                thiz.setId(thiz.getDigest());
            }

            if (thiz.getMismatchedJoins() != null) {
                List<JoinColumns> mismatchedJoins = new ArrayList<>(thiz.getMismatchedJoins().size());
                for (JoinColumns join : thiz.getMismatchedJoins()) {
                    mismatchedJoins.add(join.withDefaultSchema(defaultSchema));
                }
                thiz.setMismatchedJoins(mismatchedJoins);
            }

            if (thiz.getCriteriaColumns() != null) {
                List<ColumnReference> criteriaColumns = new ArrayList<>(thiz.getCriteriaColumns().size());
                for (ColumnReference criteriaColumn : thiz.getCriteriaColumns()) {
                    criteriaColumns.add(criteriaColumn.withDefaultSchema(defaultSchema));
                }
                thiz.setCriteriaColumns(criteriaColumns);
            }

            if (thiz.getSelectColumns() != null) {
                List<ColumnReference> selectColumns = new ArrayList<>(thiz.getSelectColumns().size());
                for (ColumnReference selectColumn : thiz.getSelectColumns()) {
                    selectColumns.add(selectColumn.withDefaultSchema(defaultSchema));
                }
                thiz.setSelectColumns(selectColumns);
            }

            if (thiz.getGroupByColumns() != null) {
                List<ColumnReference> groupByColumns = new ArrayList<>(thiz.getGroupByColumns().size());
                for (ColumnReference groupByColumn : thiz.getGroupByColumns()) {
                    groupByColumns.add(groupByColumn.withDefaultSchema(defaultSchema));
                }
                thiz.setGroupByColumns(groupByColumns);
            }

            if (thiz.getJoinColumns() != null) {
                List<JoinColumns> joinColumns = new ArrayList<>(thiz.getJoinColumns().size());
                for (JoinColumns join : thiz.getJoinColumns()) {
                    joinColumns.add(join.withDefaultSchema(defaultSchema));
                }
                thiz.setJoinColumns(joinColumns);
            }

            if (thiz.getTables() != null) {
                List<TableReference> tables = new ArrayList<>(thiz.getTables().size());
                for (TableReference table : thiz.getTables()) {
                    tables.add(table.withDefaultSchema(defaultSchema));
                }
                thiz.setTables(tables);
            }

            return thiz;
        }


        /**
         * Give every list field an empty list rather than leaving it null.
         *
         * The schema declares these {@code = []}, but the default only applies
         * to a field the builder never touched *and* only if Avro materialises
         * it — in practice a field that no analyzer populated arrives null on
         * the wire. Which fields those are depends on how the analyzer fills
         * them: `setJoinColumns` / `setMismatchedJoins` are called
         * unconditionally so those are always present, while `selectColumn`,
         * `groupByColumn` and `addSchema` create their list on first insert, so
         * a statement with none of them — or an analyzer that threw or never
         * ran — left null behind.
         *
         * That was a real bug rather than a cosmetic one: ~5% of profiles came
         * back with null `selectColumns` / `groupByColumns` / `schemas`, and
         * the SQL view read `.length` off them, throwing during change
         * detection and blanking most of the page.
         *
         * Normalising here covers every profile, because both build paths go
         * through {@code completeBuild}.
         */
        private static void emptyUnsetLists(SqlProfile.Builder thiz) {
            if (thiz.getTables() == null) { thiz.setTables(new ArrayList<>()); }
            if (thiz.getJoinColumns() == null) { thiz.setJoinColumns(new ArrayList<>()); }
            if (thiz.getCriteriaColumns() == null) { thiz.setCriteriaColumns(new ArrayList<>()); }
            if (thiz.getSelectColumns() == null) { thiz.setSelectColumns(new ArrayList<>()); }
            if (thiz.getGroupByColumns() == null) { thiz.setGroupByColumns(new ArrayList<>()); }
            if (thiz.getMismatchedJoins() == null) { thiz.setMismatchedJoins(new ArrayList<>()); }
            if (thiz.getSchemas() == null) { thiz.setSchemas(new ArrayList<>()); }
            if (thiz.getSourcedFiles() == null) { thiz.setSourcedFiles(new ArrayList<>()); }
        }

        public static SqlProfile completeBuild(@This SqlProfile.Builder thiz, String defaultSchema) {
            return complete(thiz, defaultSchema).build();
        }
    }
}
