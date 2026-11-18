package com.github.safetytrick.sqlexplorer.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;

/**
 * What differs between two definitions of one table, element by element:
 * columns, indexes and foreign keys, each described the way a person reads
 * DDL (sqlx-a6z). Schema drift says <em>that</em> clusters disagree, by
 * fingerprint; this says <em>how</em>.
 *
 * <p>Only what the loaded schema keeps can be compared. The fingerprint also
 * hashes a few things it does not — column comments among them — so two
 * structures can differ by fingerprint and still diff to nothing here. The
 * caller says so rather than showing an empty diff as agreement.
 */
public final class TableDiff {

    /**
     * One element defined differently.
     *
     * @param kind  {@code table}, {@code column}, {@code index} or {@code foreign key}
     * @param name  the element's name
     * @param base  its definition in the base table; null where the base lacks it
     * @param other its definition in the other; null where the other lacks it
     */
    public record Difference(String kind, String name, String base, String other) { }

    private TableDiff() { }

    /** Every element defined differently in {@code other} than in {@code base}, in kind then name order. */
    public static List<Difference> between(TableStructure base, TableStructure other) {
        List<Difference> differences = new ArrayList<>();
        if (!Objects.equals(blankToNull(base.remarks()), blankToNull(other.remarks()))) {
            differences.add(new Difference("table", "comment", quote(base.remarks()), quote(other.remarks())));
        }
        compare(differences, "column", base.columns(), other.columns(), TableColumn::columnName, TableDiff::describe);
        compare(differences, "index", base.indices(), other.indices(), TableIndex::name, TableDiff::describe);
        compare(differences, "foreign key", base.foreignKeys(), other.foreignKeys(), TableDiff::foreignKeyName,
            TableDiff::describe);
        return List.copyOf(differences);
    }

    private static <T> void compare(List<Difference> into, String kind, List<T> base, List<T> other,
                                    Function<T, String> name, Function<T, String> describe) {
        Map<String, String> inBase = described(base, name, describe);
        Map<String, String> inOther = described(other, name, describe);
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(inBase.keySet());
        names.addAll(inOther.keySet());
        for (String element : names) {
            String was = inBase.get(element.toLowerCase(Locale.ROOT));
            String is = inOther.get(element.toLowerCase(Locale.ROOT));
            if (!Objects.equals(was, is)) {
                into.add(new Difference(kind, element, was, is));
            }
        }
    }

    /** Keyed by lowercase name, as MySQL compares them; an element repeated by name keeps its first. */
    private static <T> Map<String, String> described(List<T> elements, Function<T, String> name,
                                                     Function<T, String> describe) {
        Map<String, String> byName = new LinkedHashMap<>();
        if (elements != null) {
            for (T element : elements) {
                byName.putIfAbsent(name.apply(element).toLowerCase(Locale.ROOT), describe.apply(element));
            }
        }
        return byName;
    }

    static String describe(TableColumn column) {
        StringBuilder text = new StringBuilder(column.typeName().toLowerCase(Locale.ROOT));
        if (column.columnSize() > 0) {
            text.append('(').append(column.columnSize());
            if (column.decimalDigits() > 0) {
                text.append(',').append(column.decimalDigits());
            }
            text.append(')');
        }
        text.append("NO".equalsIgnoreCase(column.isNullable()) ? " NOT NULL" : " NULL");
        if (column.columnDefault() != null) {
            text.append(" DEFAULT ").append(column.columnDefault());
        }
        if ("YES".equalsIgnoreCase(column.isAutoIncrement())) {
            text.append(" AUTO_INCREMENT");
        }
        if ("YES".equalsIgnoreCase(column.isGenerated())) {
            text.append(" GENERATED");
        }
        if (column.characterSet() != null) {
            text.append(" CHARSET ").append(column.characterSet());
        }
        if (column.collation() != null) {
            text.append(" COLLATE ").append(column.collation());
        }
        if (blankToNull(column.remarks()) != null) {
            text.append(" COMMENT ").append(quote(column.remarks()));
        }
        // position last: a column moved but otherwise alike should read as moved
        return text.append(" · position ").append(column.ordinal()).toString();
    }

    static String describe(TableIndex index) {
        String kind = "PRIMARY".equalsIgnoreCase(index.name()) ? "PRIMARY KEY"
            : index.unique() ? "UNIQUE" : "INDEX";
        String text = kind + " (" + String.join(", ", index.columns()) + ")";
        return index.filterCondition() == null ? text : text + " WHERE " + index.filterCondition();
    }

    static String describe(ForeignKey key) {
        String text = "(" + columns(key.from()) + ") → "
            + (key.to().isEmpty() ? "?" : tableOf(key.to().getFirst())) + " (" + columns(key.to()) + ")";
        if (key.onUpdate() != null) {
            text += " ON UPDATE " + key.onUpdate();
        }
        if (key.onDelete() != null) {
            text += " ON DELETE " + key.onDelete();
        }
        return text;
    }

    /** Its constraint name, or — when the schema's source gave none — its columns, which is what identifies it. */
    private static String foreignKeyName(ForeignKey key) {
        return key.constraintName() != null ? key.constraintName() : "(" + columns(key.from()) + ")";
    }

    private static String columns(List<ColumnReference> columns) {
        return columns.stream().map(ColumnReference::getColumn).collect(Collectors.joining(", "));
    }

    private static String tableOf(ColumnReference column) {
        String schema = column.getTable().getSchemaName();
        return schema == null ? column.getTable().getTableName() : schema + "." + column.getTable().getTableName();
    }

    private static String quote(String text) {
        return blankToNull(text) == null ? null : "'" + text + "'";
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
