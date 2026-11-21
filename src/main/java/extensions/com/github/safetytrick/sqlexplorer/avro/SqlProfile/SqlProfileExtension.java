package extensions.com.github.safetytrick.sqlexplorer.avro.SqlProfile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.github.safetytrick.sqlexplorer.analysis.parser.NormalizedQueryDigest;
import com.github.safetytrick.sqlexplorer.analysis.NameNormalizer;
import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.IndexHint;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.CriteriaComparison;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.google.common.hash.Hashing;
import extensions.com.github.safetytrick.sqlexplorer.avro.ColumnReference.ColumnReferenceExtension;
import extensions.com.github.safetytrick.sqlexplorer.avro.JoinColumns.JoinColumnsExtension;
import extensions.com.github.safetytrick.sqlexplorer.avro.TableReference.TableReferenceExtension;
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

    /**
     * How many distinct tables the statement references, by normalized schema
     * and table name, so a table named twice — a self-join, or a table read
     * again in a subquery — counts once. A table with no schema (a statement
     * with no default database) still counts.
     */
    public static int distinctTableCount(@This SqlProfile profile) {
        if (profile.getTables() == null) {
            return 0;
        }
        return (int) profile.getTables().stream()
            .filter(table -> table.getNormalizedTableName() != null)
            .map(table -> (table.getNormalizedSchemaName() + "." + table.getNormalizedTableName()).toLowerCase(Locale.ROOT))
            .distinct()
            .count();
    }

    /**
     * The tables the statement joins to themselves: a join column pair whose
     * two sides resolve, through their aliases, to the same table. One entry
     * per table however many columns it joins on.
     *
     * <p>The analyzer records an {@code IN (SELECT ...)} semi-join and a
     * correlated subquery's condition as join pairs, so those count when the
     * subquery reads the same table. A table repeated with no condition
     * between its instances (a cross join, an uncorrelated scalar subquery)
     * does not.
     */
    public static List<TableReference> selfJoinedTables(@This SqlProfile profile) {
        if (profile.getJoinColumns() == null) {
            return List.of();
        }
        List<TableReference> tables = new ArrayList<>();
        for (JoinColumns join : profile.getJoinColumns()) {
            if (join.getLeft() == null || join.getRight() == null) {
                continue;
            }
            TableReference left = join.getLeft().getTable();
            TableReference right = join.getRight().getTable();
            if (left == null || left.getNormalizedTableName() == null
                || !TableReferenceExtension.equalsNormalized(left, right)) {
                continue;
            }
            if (tables.stream().noneMatch(seen -> TableReferenceExtension.equalsNormalized(seen, left))) {
                tables.add(left);
            }
        }
        return tables;
    }

    /**
     * Whether any of the profile's index hints names {@code index} on
     * {@code table}, whatever the action. The index counterpart of
     * {@link #constrainedBy}, matching the table the same way.
     */
    public static boolean hintsIndex(@This SqlProfile profile, TableReference table, String index) {
        String normalizedIndex = NameNormalizer.normalize(index);
        return profile.getIndexHints() != null && profile.getIndexHints().stream().anyMatch(hint ->
            TableReferenceExtension.comparator.compare(hint.getTable(), table) == 0
                && hint.getNormalizedIndex().equals(normalizedIndex));
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

        public static SqlProfile.Builder orderByColumn(@This SqlProfile.Builder thiz, ColumnReference column) {
            if (thiz.getOrderByColumns() == null) {
                thiz.setOrderByColumns(new ArrayList<>());
            }
            if (thiz.getOrderByColumns().stream().noneMatch(column::equalsNormalized)) {
                thiz.getOrderByColumns().add(column);
            }
            return thiz;
        }

        /**
         * Adds a comparison unless the same column is already recorded with
         * the same kind and leading-wildcard answer: a column searched both
         * {@code LIKE 'a%'} and {@code LIKE '%a'} keeps both.
         */
        public static SqlProfile.Builder criteriaComparison(@This SqlProfile.Builder thiz, CriteriaComparison comparison) {
            if (thiz.getCriteriaComparisons() == null) {
                thiz.setCriteriaComparisons(new ArrayList<>());
            }
            boolean present = thiz.getCriteriaComparisons().stream().anyMatch(existing ->
                existing.getColumn().equalsNormalized(comparison.getColumn())
                    && existing.getKind().equals(comparison.getKind())
                    && java.util.Objects.equals(existing.getLeadingWildcard(), comparison.getLeadingWildcard()));
            if (!present) {
                thiz.getCriteriaComparisons().add(comparison);
            }
            return thiz;
        }

        /** Adds a hint unless the same action already names the same index of the same table. */
        public static SqlProfile.Builder indexHint(@This SqlProfile.Builder thiz, IndexHint hint) {
            if (thiz.getIndexHints() == null) {
                thiz.setIndexHints(new ArrayList<>());
            }
            boolean present = thiz.getIndexHints().stream().anyMatch(existing ->
                existing.getTable().equalsNormalized(hint.getTable())
                    && existing.getNormalizedIndex().equals(hint.getNormalizedIndex())
                    && existing.getAction().equals(hint.getAction()));
            if (!present) {
                thiz.getIndexHints().add(hint);
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

            if (thiz.getAnalysisFailureReason() != null) {
                thiz.setAnalysisFailed(true);
            }

            if (thiz.getRuntimeMetrics() != null) {
                thiz.setDigest(thiz.getRuntimeMetrics().getDigest());
            }

            if (thiz.getNormalizedDigest() != null) {
                thiz.setDigest(thiz.getNormalizedDigest());
            } else if (thiz.getDigest() != null) {
                String scoped = NormalizedQueryDigest.scopedDigest(thiz.getSchemaName(), thiz.getDigest());
                thiz.setNormalizedDigest(scoped);
                thiz.setDigest(scoped);
            } else {
                String queryDigest = NormalizedQueryDigest.scopedDigest(
                    thiz.getSchemaName(), calculateQueryDigest(thiz.getQuery()));
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

            if (thiz.getOrderByColumns() != null) {
                List<ColumnReference> orderByColumns = new ArrayList<>(thiz.getOrderByColumns().size());
                for (ColumnReference orderByColumn : thiz.getOrderByColumns()) {
                    orderByColumns.add(orderByColumn.withDefaultSchema(defaultSchema));
                }
                thiz.setOrderByColumns(orderByColumns);
            }

            if (thiz.getCriteriaComparisons() != null) {
                List<CriteriaComparison> comparisons = new ArrayList<>(thiz.getCriteriaComparisons().size());
                for (CriteriaComparison comparison : thiz.getCriteriaComparisons()) {
                    comparisons.add(CriteriaComparison.newBuilder(comparison)
                        .setColumn(comparison.getColumn().withDefaultSchema(defaultSchema)).build());
                }
                thiz.setCriteriaComparisons(comparisons);
            }

            if (thiz.getIndexHints() != null) {
                List<IndexHint> indexHints = new ArrayList<>(thiz.getIndexHints().size());
                for (IndexHint hint : thiz.getIndexHints()) {
                    indexHints.add(IndexHint.newBuilder(hint)
                        .setTable(hint.getTable().withDefaultSchema(defaultSchema)).build());
                }
                thiz.setIndexHints(indexHints);
            }

            if (thiz.getWriteTables() != null) {
                List<TableReference> writeTables = new ArrayList<>(thiz.getWriteTables().size());
                for (TableReference table : thiz.getWriteTables()) {
                    writeTables.add(table.withDefaultSchema(defaultSchema));
                }
                thiz.setWriteTables(writeTables);
            }

            if (thiz.getTables() != null) {
                List<TableReference> tables = new ArrayList<>(thiz.getTables().size());
                for (TableReference table : thiz.getTables()) {
                    tables.add(table.withDefaultSchema(defaultSchema));
                }
                thiz.setTables(tables);
            }

            for (TableReference table : thiz.getTables()) {
                addSchema(thiz, table.getNormalizedSchemaName());
            }

            if (thiz.getSchemas().size() > 1) {
                thiz.setIsMultiSchema(true);
            }

            if (thiz.getSchemas().size() == 1) {
                thiz.setSchemaName(thiz.getSchemas().getFirst());
            }

            return thiz;
        }


        private static void emptyUnsetLists(SqlProfile.Builder thiz) {
            if (thiz.getTables() == null) { thiz.setTables(new ArrayList<>()); }
            if (thiz.getJoinColumns() == null) { thiz.setJoinColumns(new ArrayList<>()); }
            if (thiz.getCriteriaColumns() == null) { thiz.setCriteriaColumns(new ArrayList<>()); }
            if (thiz.getSelectColumns() == null) { thiz.setSelectColumns(new ArrayList<>()); }
            if (thiz.getGroupByColumns() == null) { thiz.setGroupByColumns(new ArrayList<>()); }
            if (thiz.getMismatchedJoins() == null) { thiz.setMismatchedJoins(new ArrayList<>()); }
            if (thiz.getSchemas() == null) { thiz.setSchemas(new ArrayList<>()); }
            if (thiz.getSourcedFiles() == null) { thiz.setSourcedFiles(new ArrayList<>()); }
            if (thiz.getSources() == null) { thiz.setSources(new ArrayList<>()); }
            if (thiz.getWriteTables() == null) { thiz.setWriteTables(new ArrayList<>()); }
            if (thiz.getIndexHints() == null) { thiz.setIndexHints(new ArrayList<>()); }
            if (thiz.getOrderByColumns() == null) { thiz.setOrderByColumns(new ArrayList<>()); }
            if (thiz.getCriteriaComparisons() == null) { thiz.setCriteriaComparisons(new ArrayList<>()); }
            if (thiz.getGeneratorCandidates() == null) { thiz.setGeneratorCandidates(new ArrayList<>()); }
        }

        public static SqlProfile completeBuild(@This SqlProfile.Builder thiz, String defaultSchema) {
            return complete(thiz, defaultSchema).build();
        }
    }
}
