package extensions.com.github.safetytrick.sqlexplorer.avro.TableReference;

import java.util.Comparator;
import java.util.Objects;
import java.util.function.ToIntFunction;

import com.github.safetytrick.sqlexplorer.analysis.NameNormalizer;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import manifold.ext.rt.api.Extension;
import manifold.ext.rt.api.This;

@Extension
public class TableReferenceExtension {

    public static final Comparator<TableReference> comparator =
            Comparator.nullsFirst((TableReference thiz, TableReference other) -> {
                if (thiz.getNormalizedSchemaName() == null && other.getNormalizedSchemaName() == null) {
                    return 0;
                } else if (thiz.getNormalizedSchemaName() == null) {
                    return -1;
                } else if (other.getNormalizedSchemaName() == null) {
                    return 1;
                }
                return thiz.getNormalizedSchemaName().compareTo(other.getNormalizedSchemaName());
            })
            .thenComparing(tableReference -> tableReference != null ? tableReference.getNormalizedTableName() : null);

    public static final ToIntFunction<TableReference> hashCode =
        tableReference -> Objects.hash(tableReference.getNormalizedSchemaName(), tableReference.getNormalizedTableName());

    @Extension
    public static TableReference newTableReference(String schemaName, String tableName) {
        if (tableName == null) {
            tableName = "dual";
        }
        String normalizedSchemaName = NameNormalizer.normalize(schemaName);
        String normalizedTableName = NameNormalizer.normalize(tableName);

        return TableReference.newBuilder()
            .setTableName(tableName)
            .setSchemaName(schemaName)
            .setNormalizedSchemaName(normalizedSchemaName)
            .setNormalizedTableName(normalizedTableName).build();

    }

    public static TableReference withDefaultSchema(@This TableReference thiz, String schema) {
        if (thiz.getSchemaName() == null) {
            return newTableReference(schema, thiz.getTableName());
        }
        return thiz;
    }

    public static String toStringNormalized(@This TableReference thiz) {
        return thiz.getSchemaName() == null ?
            thiz.getNormalizedTableName() :
            thiz.getNormalizedSchemaName() + "." + thiz.getNormalizedTableName();
    }

    public static String getFullName(@This TableReference thiz) {
        return thiz.getSchemaName() == null ?
            thiz.getTableName() :
            thiz.getSchemaName() + "." + thiz.getTableName();
    }

    @Extension
    public static TableReference fromString(String reference) {
        String[] split = reference.split("\\.");
        if (split.length == 1) {
            return newTableReference(null, split[0]);
        } else if (split.length == 2) {
            return newTableReference(split[0], split[1]);
        } else {
            throw new IllegalArgumentException("Invalid reference: " + reference);
        }
    }

    public static boolean equalsNormalized(@This TableReference thiz, String other) {
        return equalsNormalized(thiz, fromString(other));
    }

    public static boolean equalsNormalized(@This TableReference thiz, TableReference other) {
        return thiz == other || (
                thiz != null && other != null &&
                Objects.equals(thiz.getNormalizedTableName(), other.getNormalizedTableName())
                && Objects.equals(thiz.getNormalizedSchemaName(), other.getNormalizedSchemaName())
        );
    }

    public static boolean isNormalized(@This TableReference thiz) {
        return thiz.getSchemaName().equals(thiz.getNormalizedSchemaName())
            && thiz.getTableName().equals(thiz.getNormalizedTableName());
    }

    public static TableReference normalized(@This TableReference thiz) {
        if (isNormalized(thiz)) {
            return thiz;
        } else {
            return TableReference.newTableReference(thiz.getNormalizedSchemaName(),
                thiz.getNormalizedTableName());
        }
    }
}
