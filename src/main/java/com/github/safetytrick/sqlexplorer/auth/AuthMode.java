package com.github.safetytrick.sqlexplorer.auth;

import java.util.Locale;

public enum AuthMode {
    NONE,
    OIDC;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
