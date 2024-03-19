package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;

import com.github.safetytrick.sqlexplorer.model.ColumnReference;
import com.github.safetytrick.sqlexplorer.model.JoinColumns;

import static org.junit.jupiter.api.Assertions.fail;

public class Assertions {

    public static void assertContainsJoinColumns(List<JoinColumns> joinColumns, String first, String second) {
        for (JoinColumns pair : joinColumns) {
            if (pair.left().equals(ColumnReference.fromString(first)) && pair.right().equals(ColumnReference.fromString(second)) ||
                pair.right().equals(ColumnReference.fromString(first)) && pair.left().equals(ColumnReference.fromString(second))
            ) {
                return;
            }
        }
        fail("join pair=%s=%s not found in pairs=%s".formatted(first, second, joinColumns));
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
