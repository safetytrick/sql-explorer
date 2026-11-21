package com.github.safetytrick.sqlexplorer.auth;

public record Grant(Role role, Scope scope) implements java.io.Serializable {

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

    public boolean includes(Grant other) {
        return role.compareTo(other.role) >= 0 && scope.covers(other.scope);
    }

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
