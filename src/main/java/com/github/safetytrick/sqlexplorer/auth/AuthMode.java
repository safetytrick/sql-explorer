package com.github.safetytrick.sqlexplorer.auth;

import java.util.Locale;

/**
 * How people sign in, {@code sql-explorer.auth.mode}.
 *
 * <ul>
 *   <li>{@link #NONE}: no sign-in. Every request is the legacy {@code admin}
 *       user, as before sqlx-e13.1. The default for now, so existing
 *       deployments keep working; it logs a warning at startup and the UI
 *       shows a banner.</li>
 *   <li>{@link #OIDC}: OpenID Connect sign-in against the client registration
 *       named {@code sql-explorer}. The API answers 401 without a session.</li>
 * </ul>
 */
public enum AuthMode {
    NONE,
    OIDC;

    /** As it appears in config and in {@code /user}: lower case. */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
