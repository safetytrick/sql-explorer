package com.github.safetytrick.sqlexplorer.schema;

public enum IndexType {
    tableIndexStatistics, tableIndexClustered, tableIndexHashed, tableIndexOther;

    public static IndexType fromIndex(short indexType) {
        return IndexType.values()[indexType];
    }
}
