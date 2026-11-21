package com.github.safetytrick.sqlexplorer.environment;

import java.util.Locale;

public enum ServerRole {
    PRIMARY,
    REPLICA,
    ANALYTICS,
    SAMPLING;

    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
