package com.github.safetytrick.sqlexplorer.auth;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import static com.github.safetytrick.sqlexplorer.auth.Permission.*;

public enum Role {

    READER(EnumSet.of(EXPLORER_READ, QUERY_READ)),
    VIEWER(with(READER, COMMENTARY_WRITE)),
    RUNNER(with(VIEWER, QUERY_RUN, ENV_EXPLAIN, ENV_SAMPLE)),
    AUTHOR(with(RUNNER, QUERY_AUTHOR)),
    ADMIN(EnumSet.allOf(Permission.class));

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = Collections.unmodifiableSet(permissions);
    }

    public Set<Permission> permissions() {
        return permissions;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Role parse(String key) {
        for (Role role : values()) {
            if (role.key().equalsIgnoreCase(key)) {
                return role;
            }
        }
        return null;
    }

    private static Set<Permission> with(Role base, Permission... more) {
        EnumSet<Permission> set = EnumSet.copyOf(base.permissions);
        Collections.addAll(set, more);
        return set;
    }
}
