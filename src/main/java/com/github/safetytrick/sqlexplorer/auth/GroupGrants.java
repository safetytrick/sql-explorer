package com.github.safetytrick.sqlexplorer.auth;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns the provider's {@code groups} claim into grants (sqlx-e13.4).
 *
 * <p>Two ways for a group to carry a grant:
 * <ul>
 *   <li><b>By name</b>, with the configured prefix ({@code sqlx:} by
 *       default): {@code <prefix><role>[:<scope>]}. {@code sqlx:admin},
 *       {@code sqlx:viewer:prod}, {@code sqlx:runner:prod/reporting}. No
 *       scope means everywhere.</li>
 *   <li><b>By mapping</b>, for groups the provider names its own way:
 *       {@code sql-explorer.auth.group-grants.<group>: [<role>[:<scope>], ...]}.</li>
 * </ul>
 *
 * <p>Re-read from the claim on every request, so it is exactly as fresh as the
 * sign-in: a group removed at the provider takes effect at the next one.
 * Anything that does not parse grants nothing and is logged once, rather than
 * failing a sign-in over a group meant for some other application.
 */
@Component
public class GroupGrants {

    private static final Logger log = LoggerFactory.getLogger(GroupGrants.class);

    private final String prefix;
    private final AuthProperties properties;
    private final Set<String> warned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public GroupGrants(AuthProperties properties) {
        this.properties = properties;
        this.prefix = properties.groupPrefix() == null ? "" : properties.groupPrefix();
    }

    public Grants of(Collection<String> groups) {
        Set<Grant> grants = new LinkedHashSet<>();
        for (String group : groups) {
            List<String> mapped = properties.groupGrants().get(group);
            if (mapped != null) {
                mapped.forEach(text -> add(grants, group, text));
            } else if (!prefix.isEmpty() && group.startsWith(prefix)) {
                add(grants, group, group.substring(prefix.length()));
            }
        }
        return new Grants(new ArrayList<>(grants));
    }

    private void add(Set<Grant> grants, String group, String text) {
        Grant grant = parse(text);
        if (grant != null) {
            grants.add(grant);
        } else if (warned.add(group + "=" + text)) {
            log.warn("group '{}' grants nothing: '{}' is not <role>[:<environment>[/<datasource>]]", group, text);
        }
    }

    /** {@code runner:prod/reporting} → Runner on that datasource; null if it does not parse. */
    static Grant parse(String text) {
        int colon = text.indexOf(':');
        Role role = Role.parse(colon < 0 ? text : text.substring(0, colon));
        Scope scope = Scope.parse(colon < 0 ? null : text.substring(colon + 1));
        return role == null || scope == null ? null : new Grant(role, scope);
    }
}
