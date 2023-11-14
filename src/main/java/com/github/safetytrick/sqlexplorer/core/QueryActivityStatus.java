package com.github.safetytrick.sqlexplorer.core;

public enum QueryActivityStatus {
    ACTIVE, DORMANT, UNKNOWN;

    public boolean isActive() {
        return this == ACTIVE;
    }
}
