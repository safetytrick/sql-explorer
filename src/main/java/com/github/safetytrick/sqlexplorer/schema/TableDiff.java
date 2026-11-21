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

public final class TableDiff {

    public record Difference(String kind, String name, String base, String other) { }

    private TableDiff() { }

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
