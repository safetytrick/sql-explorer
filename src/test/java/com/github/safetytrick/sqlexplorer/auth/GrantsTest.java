package com.github.safetytrick.sqlexplorer.auth;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GrantsTest {

    private final GroupGrants mapper = new GroupGrants(new AuthProperties(AuthMode.OIDC, null, null, "/", "sqlx:",
        Map.of("dba-team", List.of("admin"), "eng", List.of("viewer:prod", "runner:staging")), null));

    @Test
    void rolesNest() {
        assertThat(Role.RUNNER.permissions()).containsAll(Role.VIEWER.permissions());
        assertThat(Role.AUTHOR.permissions()).containsAll(Role.RUNNER.permissions());
        assertThat(Role.ADMIN.permissions()).containsAll(Role.AUTHOR.permissions()).contains(Permission.ENV_PULL);
        assertThat(Role.AUTHOR.permissions()).doesNotContain(Permission.ENV_PULL, Permission.ADMIN);
    }

    @Test
    void groupsWithThePrefixNameTheirGrant() {
        Grants grants = mapper.of(List.of("sqlx:viewer:prod", "sqlx:runner:prod/reporting", "sqlx:admin", "unrelated"));
        assertThat(grants.grants()).extracting(Grant::toString)
            .containsExactly("viewer:prod", "runner:prod/reporting", "admin:*");
    }

    @Test
    void mappedGroupsCarryTheirConfiguredGrants() {
        assertThat(mapper.of(List.of("eng")).grants()).extracting(Grant::toString).containsExactly("viewer:prod", "runner:staging");
        assertThat(mapper.of(List.of("dba-team")).admin()).isTrue();
    }

    @Test
    void whatDoesNotParseGrantsNothing() {
        assertThat(mapper.of(List.of("sqlx:owner:prod", "sqlx:viewer:a/b/c", "sqlx:viewer:/x", "sqlx:")).grants()).isEmpty();
    }

    @Test
    void aGrantReachesDownItsScopeButNeverUp() {
        Grants grants = mapper.of(List.of("sqlx:viewer:prod", "sqlx:author:staging/app"));
        assertThat(grants.allows(Permission.EXPLORER_READ, "prod")).isTrue();
        assertThat(grants.allows(Permission.QUERY_READ, Scope.datasource("prod", "reporting"))).isTrue();
        assertThat(grants.allows(Permission.EXPLORER_READ, "staging")).as("a datasource grant is not the environment").isFalse();
        assertThat(grants.allows(Permission.QUERY_AUTHOR, Scope.datasource("staging", "app"))).isTrue();
        assertThat(grants.allows(Permission.QUERY_AUTHOR, Scope.datasource("staging", "other"))).isFalse();
        assertThat(grants.allows(Permission.EXPLORER_READ, "dev")).isFalse();
        assertThat(grants.admin()).isFalse();
    }

    @Test
    void aGlobalGrantReachesEveryEnvironment() {
        Grants grants = mapper.of(List.of("sqlx:runner"));
        assertThat(grants.allows(Permission.ENV_EXPLAIN, "anything")).isTrue();
        assertThat(grants.admin()).as("Runner everywhere is still not Admin").isFalse();
    }
}
