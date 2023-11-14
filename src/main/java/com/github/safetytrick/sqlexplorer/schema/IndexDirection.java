package com.github.safetytrick.sqlexplorer.schema;

public enum IndexDirection {
    ASCENDING, DESCENDING, NONE;

    public static IndexDirection fromKey(String ascOrDesc) {
        if (ascOrDesc == null) {
            return NONE;
        }
        return "A".equals(ascOrDesc) ? ASCENDING : "D".equals(ascOrDesc) ? DESCENDING : NONE;
    }
}
