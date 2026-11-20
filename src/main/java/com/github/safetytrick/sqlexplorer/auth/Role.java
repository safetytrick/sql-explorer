package com.github.safetytrick.sqlexplorer.auth;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import static com.github.safetytrick.sqlexplorer.auth.Permission.*;

/**
 * The roles, each a fixed set of {@link Permission}s, each including the one
 * before it (sqlx-e13.2, sqlx-e13.4). Fixed in code, not configurable: what a
 * role means is part of the product, and groups only say who has it where.
 * Declaration order is strength order: {@link Grant#includes} compares them.
 */
public enum Role {

    /**
     * Reads, and writes nothing. What visitors who are not signed in get
     * (sqlx-2lc): a write needs someone to author it, and without a sign-in
     * it would be stamped as the legacy admin.
     */
    READER(EnumSet.of(EXPLORER_READ, QUERY_READ)),
    /** Reads, and tags, notes and annotates. */
    VIEWER(with(READER, COMMENTARY_WRITE)),
    /** Also runs things against the servers: saved queries, EXPLAIN, sampling. */
    RUNNER(with(VIEWER, QUERY_RUN, ENV_EXPLAIN, ENV_SAMPLE)),
    AUTHOR(with(RUNNER, QUERY_AUTHOR)),
    /** Everything, including pulling environments live. */
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

    /** {@code viewer}, any case, or null. */
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
