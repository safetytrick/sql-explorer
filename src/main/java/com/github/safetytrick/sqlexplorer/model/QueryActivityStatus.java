package com.github.safetytrick.sqlexplorer.model;

public enum QueryActivityStatus {
    ACTIVE, DORMANT, UNKNOWN;

    public boolean isActive() {
        return this == ACTIVE;
    }
}
