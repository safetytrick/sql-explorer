package com.github.safetytrick.sqlexplorer.auth;

import java.util.List;

/** Everything a request is allowed, as the grants that allow it. */
public record Grants(List<Grant> grants) {

    /** Sign-in off: everyone may do everything, as before (sqlx-e13.1). */
    public static final Grants EVERYTHING = new Grants(List.of(new Grant(Role.ADMIN, Scope.GLOBAL)));
    public static final Grants NOTHING = new Grants(List.of());

    public Grants {
        grants = List.copyOf(grants);
    }

    public boolean allows(Permission permission, Scope target) {
        return grants.stream().anyMatch(g -> g.allows(permission, target));
    }

    public boolean allows(Permission permission, String environment) {
        return allows(permission, Scope.environment(environment));
    }

    public boolean admin() {
        return allows(Permission.ADMIN, Scope.GLOBAL);
    }
}
