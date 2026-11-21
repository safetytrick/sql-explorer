package com.github.safetytrick.sqlexplorer.auth;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record Grants(List<Grant> grants) implements java.io.Serializable {

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

    public Grants intersect(Grants other) {
        Set<Grant> both = new LinkedHashSet<>();
        for (Grant mine : grants) {
            for (Grant theirs : other.grants) {
                Grant meet = mine.meet(theirs);
                if (meet != null) {
                    both.add(meet);
                }
            }
        }
        List<Grant> kept = new ArrayList<>();
        for (Grant grant : both) {
            boolean redundant = both.stream().anyMatch(g -> !g.equals(grant) && g.includes(grant));
            if (!redundant) {
                kept.add(grant);
            }
        }
        return new Grants(kept);
    }

    public boolean includes(Grants other) {
        return other.grants.stream().allMatch(this::includes);
    }

    public boolean includes(Grant grant) {
        return grants.stream().anyMatch(g -> g.includes(grant));
    }

    public boolean admin() {
        return allows(Permission.ADMIN, Scope.GLOBAL);
    }
}
