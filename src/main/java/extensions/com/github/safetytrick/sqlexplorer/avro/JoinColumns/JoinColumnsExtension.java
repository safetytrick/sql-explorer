package extensions.com.github.safetytrick.sqlexplorer.avro.JoinColumns;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import extensions.com.github.safetytrick.sqlexplorer.avro.ColumnReference.ColumnReferenceExtension;
import manifold.ext.rt.api.Extension;
import manifold.ext.rt.api.This;

@Extension
public class JoinColumnsExtension {

    public static boolean contains(@This JoinColumns joinColumns, ColumnReference column) {
        return ColumnReferenceExtension.comparator.compare(column, joinColumns.getLeft()) == 0
            || ColumnReferenceExtension.comparator.compare(column, joinColumns.getRight()) == 0;
    }

    @Extension
    public static JoinColumns fromReferences(String left, String right) {
        return new JoinColumns(ColumnReferenceExtension.fromString(left),
            ColumnReferenceExtension.fromString(right));
    }

    public static JoinColumns withDefaultSchema(@This JoinColumns thiz, String schema) {
        if (thiz.getLeft().getTable().getSchemaName() == null || thiz.getRight().getTable().getSchemaName() == null) {
            return new JoinColumns(
                ColumnReferenceExtension.withDefaultSchema(thiz.getLeft(), schema),
                ColumnReferenceExtension.withDefaultSchema(thiz.getRight(), schema)
            );
        }
        return thiz;
    }

    public static String toStringNormalized(@This JoinColumns thiz) {
        return thiz.getLeft().toStringNormalized() + "=" + thiz.getRight().toStringNormalized();
    }
}
