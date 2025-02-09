package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;

import static org.junit.jupiter.api.Assertions.fail;

public class Assertions {

    public static void assertContainsJoinColumns(List<JoinColumns> joinColumns, String first, String second) {
        for (JoinColumns pair : joinColumns) {
            if (pair.getLeft().equalsNormalized(ColumnReference.fromString(first)) && pair.getRight().equalsNormalized(ColumnReference.fromString(second)) ||
                pair.getRight().equalsNormalized(ColumnReference.fromString(first)) && pair.getLeft().equalsNormalized(ColumnReference.fromString(second))
            ) {
                return;
            }
        }
        fail("join pair=%s=%s not found in pairs=%s".formatted(first, second, joinColumns));
    }

    public static void assertContainsColumnReferences(List<ColumnReference> criteriaColumns, String... columns) {
        for (String column : columns) {
            boolean found = criteriaColumns.stream().anyMatch(ref -> ref.toStringNormalized().equals(column));
            if (!found) {
                fail(column + " not found in: " + criteriaColumns);
            }
        }
    }
}
