package com.github.safetytrick.sqlexplorer.auth;

/** A role on a scope: Viewer on {@code prod}, Runner on {@code prod/reporting}, Admin everywhere. */
public record Grant(Role role, Scope scope) implements java.io.Serializable {

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

    /**
     * Whether this grant allows at least everything {@code other} does: a role
     * at least as strong, on a scope that covers the other's. Roles nest
     * (Viewer ⊂ Runner ⊂ Author ⊂ Admin), so "at least as strong" is their
     * order.
     */
    public boolean includes(Grant other) {
        return role.compareTo(other.role) >= 0 && scope.covers(other.scope);
    }

    /**
     * What this grant and {@code other} both allow, as one grant: the weaker
     * role on the narrower scope. Null when neither scope covers the other, as
     * then no target is under both.
     *
     * <p>Exact, not an approximation: a permission on a target is allowed by
     * both grants exactly when it is allowed by this one. Both scopes covering
     * the target means one covers the other (scopes are paths), the weaker role
     * holds the permission when both do, and the narrower scope passes the
     * permission's level check when both scopes do. {@code GrantsTest} checks
     * it exhaustively over a small universe.
     */
    public Grant meet(Grant other) {
        Role weaker = role.compareTo(other.role) <= 0 ? role : other.role;
        if (scope.covers(other.scope)) {
            return new Grant(weaker, other.scope);
        }
        if (other.scope.covers(scope)) {
            return new Grant(weaker, scope);
        }
        return null;
    }

    @Override
    public String toString() {
        return role.key() + ":" + scope;
    }
}
