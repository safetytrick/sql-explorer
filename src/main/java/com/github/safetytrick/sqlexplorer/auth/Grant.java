package com.github.safetytrick.sqlexplorer.auth;

/** A role on a scope: Viewer on {@code prod}, Runner on {@code prod/reporting}, Admin everywhere. */
public record Grant(Role role, Scope scope) {

    /**
     * Whether this grant allows {@code permission} on {@code target}. The
     * permission's own level decides what it can be held on: an environment
     * permission needs a grant on that environment or wider, so Runner on one
     * datasource does not make its whole environment readable. That is
     * deliberate; a group that should see the environment says so with its own
     * Viewer grant.
     */
    public boolean allows(Permission permission, Scope target) {
        if (!role.permissions().contains(permission) || !scope.covers(target)) {
            return false;
        }
        return switch (permission.level()) {
            case GLOBAL -> scope.environment() == null;
            case ENVIRONMENT -> scope.datasource() == null;
            case DATASOURCE -> true;
        };
    }

    @Override
    public String toString() {
        return role.key() + ":" + scope;
    }
}
