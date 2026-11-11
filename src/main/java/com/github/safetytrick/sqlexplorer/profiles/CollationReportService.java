package com.github.safetytrick.sqlexplorer.profiles;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryService;
import com.github.safetytrick.sqlexplorer.schema.PresumedKind;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaService;
import com.github.safetytrick.sqlexplorer.schema.TableColumn;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import org.springframework.stereotype.Service;

/**
 * Tables by the character sets and collations of their columns, and the joins
 * whose two sides disagree (sqlx-n5j.5).
 *
 * <p>Everything here is read from the schema snapshot's column metadata plus
 * the join columns analysis found in the query corpus, so it needs no live
 * server and answers from a cached environment.
 *
 * <p>THE TABLE DEFAULT. A column is called out when it differs from its
 * table's declared default collation, {@code information_schema.TABLES
 * TABLE_COLLATION}, captured with the snapshot's table statistics
 * (sqlx-n5j.2). A snapshot written before those statistics were kept, or a
 * table whose shards disagree, has no single declared default; the baseline
 * then falls back to the <em>predominant</em> collation, the one most of its
 * character columns use, and {@link TableEncoding#baseline()} says which was
 * used, so nobody mistakes one for the other.
 */
@Service
public class CollationReportService {

    private final SqlSchemaService sqlSchemaService;
    private final SqlRepositoryService sqlRepositoryService;

    public CollationReportService(SqlSchemaService sqlSchemaService, SqlRepositoryService sqlRepositoryService) {
        this.sqlSchemaService = sqlSchemaService;
        this.sqlRepositoryService = sqlRepositoryService;
    }

    public CollationReport getReport() {
        List<TableEncoding> tables = sqlSchemaService.getTables().stream()
            .filter(Objects::nonNull)
            .map(CollationReportService::encodingOf)
            .sorted(Comparator.comparing(TableEncoding::schema, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(TableEncoding::table, String.CASE_INSENSITIVE_ORDER))
            .toList();
        return new CollationReport(tables, mismatchedJoins());
    }

    /** One table's character columns, grouped by charset and collation. */
    static TableEncoding encodingOf(TableStructure structure) {
        Map<String, List<String>> columnsByEncoding = new LinkedHashMap<>();
        Map<String, EncodingKey> keys = new HashMap<>();
        List<TableColumn> textColumns = new ArrayList<>();
        for (TableColumn column : structure.columns() == null ? List.<TableColumn>of() : structure.columns()) {
            if (column.characterSet() == null && column.collation() == null) {
                continue;
            }
            textColumns.add(column);
            EncodingKey key = new EncodingKey(column.characterSet(), column.collation());
            keys.putIfAbsent(key.id(), key);
            columnsByEncoding.computeIfAbsent(key.id(), id -> new ArrayList<>()).add(column.columnName());
        }

        List<EncodingCount> encodings = columnsByEncoding.entrySet().stream()
            .map(entry -> new EncodingCount(keys.get(entry.getKey()).characterSet(),
                keys.get(entry.getKey()).collation(), List.copyOf(entry.getValue())))
            // most columns first; ties by name, so the predominant one is stable
            .sorted(Comparator.comparingInt((EncodingCount count) -> count.columns().size()).reversed()
                .thenComparing(count -> String.valueOf(count.collation())))
            .toList();

        Set<String> charsets = new HashSet<>();
        Set<String> collations = new HashSet<>();
        for (TableColumn column : textColumns) {
            charsets.add(lower(column.characterSet()));
            collations.add(lower(column.collation()));
        }

        EncodingCount predominant = encodings.isEmpty() ? null : encodings.getFirst();
        String declaredCollation = declaredCollation(structure);
        String declaredCharset = charsetOf(declaredCollation);

        // the baseline a column is compared with: the declared default when
        // there is one, else the predominant encoding
        String baseCollation = declaredCollation != null ? declaredCollation
            : predominant == null ? null : predominant.collation();
        String baseCharset = declaredCollation != null ? declaredCharset
            : predominant == null ? null : predominant.characterSet();
        boolean compare = declaredCollation != null ? !textColumns.isEmpty() : encodings.size() > 1;
        List<ColumnEncoding> outliers = !compare ? List.of()
            : textColumns.stream()
                .filter(column -> !Objects.equals(lower(column.collation()), lower(baseCollation))
                    || !Objects.equals(lower(column.characterSet()), lower(baseCharset)))
                .map(column -> new ColumnEncoding(column.columnName(), column.characterSet(), column.collation()))
                .toList();
        Set<String> outlierNames = new HashSet<>();
        outliers.forEach(outlier -> outlierNames.add(outlier.column()));
        List<TextColumn> columns = textColumns.stream()
            .map(column -> {
                PresumedKind.Presumption presumed =
                    PresumedKind.of(column.columnName(), column.typeName(), column.columnSize());
                return new TextColumn(column.columnName(), typeOf(column), column.characterSet(),
                    column.collation(), presumed.kind(), presumed.basis(),
                    outlierNames.contains(column.columnName()));
            })
            .toList();

        return new TableEncoding(structure.schema(), structure.name(),
            "VIEW".equalsIgnoreCase(structure.type()),
            textColumns.size(),
            predominant == null ? null : predominant.characterSet(),
            predominant == null ? null : predominant.collation(),
            declaredCharset, declaredCollation,
            declaredCollation != null ? "declared" : "predominant",
            charsets.size() > 1, collations.size() > 1,
            encodings, outliers, columns);
    }

    /** VARCHAR(255), ENUM, TEXT: the declared type as the snapshot recorded it. */
    private static String typeOf(TableColumn column) {
        String type = column.typeName() == null ? "?" : column.typeName();
        String lower = type.toLowerCase(Locale.ROOT);
        boolean sized = (lower.equals("varchar") || lower.equals("char")) && column.columnSize() > 0;
        return sized ? type + "(" + column.columnSize() + ")" : type;
    }

    /**
     * Join conditions in the corpus whose two columns are both character
     * columns and differ in charset or collation, one row per column pair
     * however many queries join on it.
     */
    private List<JoinEncodingMismatch> mismatchedJoins() {
        Map<String, JoinAccumulator> pairs = new LinkedHashMap<>();
        Map<String, TableStructure> structures = new HashMap<>();
        sqlRepositoryService.getAllAnalyzedSql().forEach(profile -> {
            if (profile.getJoinColumns() == null) {
                return;
            }
            Set<String> countedThisQuery = new HashSet<>();
            for (JoinColumns join : profile.getJoinColumns()) {
                Resolved left = resolve(join.getLeft(), profile.getSchemaName(), structures);
                Resolved right = resolve(join.getRight(), profile.getSchemaName(), structures);
                if (left == null || right == null || !isText(left.column()) || !isText(right.column())) {
                    continue;
                }
                boolean charsetDiffers = !sameName(left.column().characterSet(), right.column().characterSet());
                boolean collationDiffers = !sameName(left.column().collation(), right.column().collation());
                if (!charsetDiffers && !collationDiffers) {
                    continue;
                }
                // one row per unordered pair: a = b and b = a are the same join key
                boolean swap = left.key().compareTo(right.key()) > 0;
                Resolved first = swap ? right : left;
                Resolved second = swap ? left : right;
                String pairKey = first.key() + "=" + second.key();
                JoinAccumulator accumulator = pairs.computeIfAbsent(pairKey,
                    key -> new JoinAccumulator(first, second, charsetDiffers));
                if (countedThisQuery.add(pairKey)) {
                    accumulator.add(profile);
                }
            }
        });
        return pairs.values().stream()
            .map(JoinAccumulator::result)
            .sorted(Comparator.comparingLong(JoinEncodingMismatch::executions).reversed()
                .thenComparing(Comparator.comparingInt(JoinEncodingMismatch::queries).reversed()))
            .toList();
    }

    private Resolved resolve(ColumnReference reference, String defaultSchema, Map<String, TableStructure> cache) {
        if (reference == null || reference.getTable() == null) {
            return null;
        }
        TableReference table = reference.getTable().withDefaultSchema(defaultSchema);
        String cacheKey = table.getNormalizedSchemaName() + "." + table.getNormalizedTableName();
        TableStructure structure = cache.computeIfAbsent(cacheKey, key -> {
            TableStructure found = sqlSchemaService.getTable(table);
            return found == null ? TableStructure.builder().build() : found;
        });
        if (structure.columns() == null) {
            return null;
        }
        String name = reference.getNormalizedColumn() != null ? reference.getNormalizedColumn() : reference.getColumn();
        if (name == null) {
            return null;
        }
        return structure.columns().stream()
            .filter(column -> column.columnName().equalsIgnoreCase(name))
            .findFirst()
            .map(column -> new Resolved(structure.schema(), structure.name(), column))
            .orElse(null);
    }

    private static boolean isText(TableColumn column) {
        return column.characterSet() != null || column.collation() != null;
    }

    private static boolean sameName(String a, String b) {
        return Objects.equals(lower(a), lower(b));
    }

    /** A join column found in the schema, named by the table that holds it. */
    private record Resolved(String schema, String table, TableColumn column) {
        String key() {
            return lower(schema) + "." + lower(table) + "." + lower(column.columnName());
        }

        JoinSide side() {
            return new JoinSide(schema, table, column.columnName(), column.characterSet(), column.collation());
        }
    }

    /**
     * The table's declared default collation, when every server that reported
     * the table agrees on one; null when none reported it (a view, an older
     * snapshot) or the shards disagree.
     */
    static String declaredCollation(TableStructure structure) {
        if (structure.stats() == null) {
            return null;
        }
        Set<String> declared = new HashSet<>();
        structure.stats().forEach(stats -> {
            if (stats.tableCollation() != null) {
                declared.add(stats.tableCollation());
            }
        });
        return declared.size() == 1 ? declared.iterator().next() : null;
    }

    /** A collation's character set: MySQL names every collation after it, {@code latin1_swedish_ci} → latin1. */
    static String charsetOf(String collation) {
        if (collation == null) {
            return null;
        }
        int at = collation.indexOf('_');
        return at < 0 ? collation : collation.substring(0, at);
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    public record CollationReport(List<TableEncoding> tables, List<JoinEncodingMismatch> joins) { }

    /**
     * One table's character columns by encoding.
     *
     * @param textColumns           columns carrying a charset or collation; 0 for a table of numbers and dates
     * @param predominantCharset    the charset of the most-used encoding
     * @param predominantCollation  likewise
     * @param declaredCharset       the charset of the table's declared default collation; null when unknown
     * @param declaredCollation     TABLE_COLLATION, when every server reporting the table agrees; else null
     * @param baseline              what the outliers were compared with: "declared" or "predominant"
     * @param mixedCharsets         its character columns use more than one charset
     * @param mixedCollations       … or more than one collation
     * @param encodings             charset + collation with the columns using it, most columns first
     * @param outliers              the columns not on the baseline encoding
     * @param columns               every character column, in table order, with its presumed kind
     */
    public record TableEncoding(String schema, String table, boolean view, int textColumns,
                                String predominantCharset, String predominantCollation,
                                String declaredCharset, String declaredCollation, String baseline,
                                boolean mixedCharsets, boolean mixedCollations,
                                List<EncodingCount> encodings, List<ColumnEncoding> outliers,
                                List<TextColumn> columns) { }

    /**
     * One character column with what it presumably holds (PresumedKind):
     * inferred from its name and type, never from its data.
     *
     * @param differs it is one of the table's outliers
     */
    public record TextColumn(String column, String type, String characterSet, String collation,
                             String kind, String basis, boolean differs) { }

    public record EncodingCount(String characterSet, String collation, List<String> columns) { }

    public record ColumnEncoding(String column, String characterSet, String collation) { }

    public record JoinSide(String schema, String table, String column, String characterSet, String collation) { }

    /**
     * A join key whose two columns differ in encoding.
     *
     * @param charsetDiffers the character sets differ (otherwise only the collation does)
     * @param queries        distinct queries joining on it
     * @param executions     their executions, summed
     * @param exampleDigest  the most-executed such query, to open
     */
    public record JoinEncodingMismatch(JoinSide left, JoinSide right, boolean charsetDiffers,
                                       int queries, long executions, String exampleDigest) { }

    private record EncodingKey(String characterSet, String collation) {
        String id() {
            return lower(characterSet) + "/" + lower(collation);
        }
    }

    private static final class JoinAccumulator {
        private final Resolved left;
        private final Resolved right;
        private final boolean charsetDiffers;
        private int queries;
        private long executions;
        private String exampleDigest;
        private long exampleExecutions = -1;

        JoinAccumulator(Resolved left, Resolved right, boolean charsetDiffers) {
            this.left = left;
            this.right = right;
            this.charsetDiffers = charsetDiffers;
        }

        void add(SqlProfile profile) {
            queries++;
            RuntimeMetrics metrics = profile.getRuntimeMetrics();
            long executed = metrics == null ? 0 : metrics.getExecuted();
            executions += executed;
            if (executed > exampleExecutions) {
                exampleExecutions = executed;
                exampleDigest = profile.getDigest();
            }
        }

        JoinEncodingMismatch result() {
            return new JoinEncodingMismatch(left.side(), right.side(), charsetDiffers,
                queries, executions, exampleDigest);
        }
    }
}
