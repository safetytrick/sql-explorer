package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;

import com.github.safetytrick.sqlexplorer.core.ColumnReference;
import com.github.safetytrick.sqlexplorer.core.JoinColumns;
import static org.junit.jupiter.api.Assertions.fail;

public class Assertions {

    public static void assertContainsJoinColumns(List<JoinColumns> joinColumns, String first, String second) {
        for (JoinColumns pair : joinColumns) {
            if (pair.getLeft().equals(ColumnReference.fromString(first)) && pair.getRight().equals(ColumnReference.fromString(second)) ||
                pair.getRight().equals(ColumnReference.fromString(first)) && pair.getLeft().equals(ColumnReference.fromString(second))
            ) {
                return;
            }
        }
        fail(String.format("join pair=%s=%s not found in pairs=%s", first, second, joinColumns));
    }

    public static void assertContainsColumnReferences(List<ColumnReference> criteriaColumns, String... columns) {
        for (String column : columns) {
            boolean found = criteriaColumns.stream().anyMatch(ref -> ref.getNormalizedReference().equals(column));
            if (!found) {
                fail(column + " not found in: " + criteriaColumns);
            }
        }
    }
}
