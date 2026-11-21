package com.github.safetytrick.sqlexplorer.auth;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GroupGrants {

    private static final Logger log = LoggerFactory.getLogger(GroupGrants.class);

    private final String prefix;
    private final AuthProperties properties;
    private final Set<String> warned = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Grants anonymous;

    public GroupGrants(AuthProperties properties) {
        this.properties = properties;
        this.prefix = properties.groupPrefix() == null ? "" : properties.groupPrefix();
        this.anonymous = anonymous(properties.anonymous());
        if (properties.oidc() && !anonymous.grants().isEmpty()) {
            log.warn("Visitors who are not signed in may read: {} (sql-explorer.auth.anonymous)", anonymous.grants());
        }
    }

    public Grants anonymous() {
        return anonymous;
    }

    static Grants anonymous(List<String> configured) {
        List<Grant> grants = new ArrayList<>();
        for (String text : configured) {
            Grant grant = parse(text);
            if (grant == null) {
                throw new IllegalStateException("sql-explorer.auth.anonymous: '" + text
                    + "' is not <role>[:<environment>[/<datasource>]], e.g. viewer or viewer:prod");
            }
            grants.add(grant);
        }
        return new Grants(grants).intersect(new Grants(List.of(new Grant(Role.READER, Scope.GLOBAL))));
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

    static Grant parse(String text) {
        int colon = text.indexOf(':');
        Role role = Role.parse(colon < 0 ? text : text.substring(0, colon));
        Scope scope = Scope.parse(colon < 0 ? null : text.substring(colon + 1));
        return role == null || scope == null ? null : new Grant(role, scope);
    }
}
