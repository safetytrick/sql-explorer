package extensions.com.github.safetytrick.sqlexplorer.avro.ColumnReference;

import java.util.Comparator;
import java.util.Objects;
import java.util.function.ToIntFunction;

import com.github.safetytrick.sqlexplorer.analysis.NameNormalizer;
import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import extensions.com.github.safetytrick.sqlexplorer.avro.TableReference.TableReferenceExtension;
import jakarta.annotation.Nullable;
import manifold.ext.rt.api.Extension;
import manifold.ext.rt.api.This;

@Extension
public class ColumnReferenceExtension {

    public static final Comparator<ColumnReference> comparator = (thiz, other) -> {
        int tableCompare = TableReferenceExtension.comparator.compare(thiz.getTable(), other.getTable());
        if (tableCompare != 0) {
            return tableCompare;
        }
        return thiz.getNormalizedColumn().compareTo(other.getNormalizedColumn());
    };

    public static final ToIntFunction<ColumnReference> hashCode =
        columnReference -> Objects.hash(columnReference.getTable(), columnReference.getNormalizedColumn());

    @Extension
    public static ColumnReference newColumnReference(TableReference tableReference, String columnName) {
        return ColumnReference.newBuilder()
            .setTable(tableReference)
            .setColumn(columnName)
            .setNormalizedColumn(NameNormalizer.normalize(columnName))
            .build();
    }

    /**
     * @param reference Either a two or three part column reference, table.column, or schema.table.column
     * @return a ColumnReference object from components
     * @throws IllegalArgumentException if the string is not a two or three part reference
     */
    @Extension
    public static ColumnReference fromString(String reference) {
        String[] split = reference.split("\\.");
        if (split.length == 2) {
            return fromComponents(null, split[0], split[1]);
        } else if (split.length == 3) {
            return fromComponents(split[0], split[1], split[2]);
        } else {
            throw new IllegalArgumentException("invalid column reference: " + reference);
        }
    }

    @Extension
    public static ColumnReference fromComponents(@Nullable String schema, String table, String column) {
        return newColumnReference(TableReferenceExtension.newTableReference(schema, table), column);
    }

    public static ColumnReference withDefaultSchema(@This ColumnReference thiz, String schema) {
        if (thiz.getTable().getSchemaName() == null) {
            return newColumnReference(
                TableReferenceExtension.withDefaultSchema(thiz.getTable(), schema),
                thiz.getColumn());
        }
        return thiz;
    }

    public static String toStringNormalized(@This ColumnReference thiz) {
        return TableReferenceExtension.toStringNormalized(thiz.getTable()) +
            "." + thiz.getNormalizedColumn();
    }

    public static boolean equalsNormalized(@This ColumnReference thiz, ColumnReference other) {
        return thiz == other || (
            thiz != null && other != null &&
            Objects.equals(thiz.getNormalizedColumn(), other.getNormalizedColumn())
            && TableReferenceExtension.equalsNormalized(thiz.getTable(), other.getTable())
        );
    }

    public static boolean equalsNormalized(@This ColumnReference thiz, String other) {
        return equalsNormalized(thiz, ColumnReference.fromString(other));
    }

    public static boolean isNormalized(@This ColumnReference thiz) {
        return TableReferenceExtension.isNormalized(thiz.getTable())
            && thiz.getColumn().equals(thiz.getNormalizedColumn());
    }

    public static ColumnReference normalized(@This ColumnReference thiz) {
        if (isNormalized(thiz)) {
            return thiz;
        } else {
            return ColumnReference.newColumnReference(
                TableReferenceExtension.normalized(thiz.getTable()),
                thiz.getNormalizedColumn()
            );
        }
    }
}
