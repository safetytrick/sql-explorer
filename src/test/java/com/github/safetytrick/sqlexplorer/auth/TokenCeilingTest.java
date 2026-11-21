package com.github.safetytrick.sqlexplorer.auth;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenCeilingTest {

    private static Grants grants(String... texts) {
        return AccessTokenService.parseCeiling(List.of(texts));
    }

    private static List<String> text(Grants grants) {
        return grants.grants().stream().map(Grant::toString).toList();
    }

    @Test
    void anAdminsViewerTokenOnlyViews() {
        Grants effective = grants("admin").intersect(grants("viewer:prod"));
        assertThat(text(effective)).containsExactly("viewer:prod");
        assertThat(effective.allows(Permission.EXPLORER_READ, "prod")).isTrue();
        assertThat(effective.allows(Permission.COMMENTARY_WRITE, "prod")).isTrue();
        assertThat(effective.allows(Permission.ENV_PULL, "prod")).isFalse();
        assertThat(effective.allows(Permission.EXPLORER_READ, "staging")).isFalse();
        assertThat(effective.admin()).isFalse();
    }

    @Test
    void theOwnerLosingAGroupNarrowsTheToken() {
        Grants ceiling = grants("runner:prod", "viewer:staging");
        assertThat(text(grants("admin").intersect(ceiling))).containsExactly("runner:prod", "viewer:staging");
        assertThat(text(grants("viewer:prod").intersect(ceiling))).containsExactly("viewer:prod");
        assertThat(grants().intersect(ceiling).grants()).isEmpty();
    }

    @Test
    void theNarrowerScopeAndTheWeakerRoleWin() {
        assertThat(text(grants("viewer:prod").intersect(grants("author:prod/app")))).containsExactly("viewer:prod/app");
        assertThat(text(grants("runner:prod/app").intersect(grants("viewer:staging")))).isEmpty();
        assertThat(text(grants("admin:*").intersect(grants("admin")))).containsExactly("admin:*");
    }

    @Test
    void theIntersectionAllowsExactlyWhatBothDo() {
        List<Scope> scopes = List.of(Scope.GLOBAL, Scope.environment("prod"), Scope.environment("staging"),
            Scope.datasource("prod", "app"), Scope.datasource("prod", "other"));
        List<Grant> universe = new ArrayList<>();
        for (Role role : Role.values()) {
            for (Scope scope : scopes) {
                universe.add(new Grant(role, scope));
            }
        }
        List<Grants> sets = new ArrayList<>();
        sets.add(Grants.NOTHING);
        for (Grant a : universe) {
            sets.add(new Grants(List.of(a)));
            for (Grant b : universe) {
                if (universe.indexOf(b) > universe.indexOf(a) && (a.role().ordinal() + universe.indexOf(b)) % 3 == 0) {
                    sets.add(new Grants(List.of(a, b)));
                }
            }
        }
        for (Grants owner : sets) {
            for (Grants ceiling : sets) {
                Grants both = owner.intersect(ceiling);
                for (Permission permission : Permission.values()) {
                    for (Scope target : scopes) {
                        assertThat(both.allows(permission, target))
                            .as("%s ∩ %s: %s on %s", owner, ceiling, permission, target)
                            .isEqualTo(owner.allows(permission, target) && ceiling.allows(permission, target));
                    }
                }
            }
        }
    }

    @Test
    void aCeilingIsWithinTheOwnersGrantsOnlyIfEachOfItsGrantsIs() {
        Grants owner = grants("viewer:prod", "runner:prod/app");
        assertThat(owner.includes(grants("viewer:prod"))).isTrue();
        assertThat(owner.includes(grants("viewer:prod/app", "runner:prod/app"))).isTrue();
        assertThat(owner.includes(grants("runner:prod"))).as("Runner on one datasource is not Runner on all of them").isFalse();
        assertThat(owner.includes(grants("viewer:staging"))).isFalse();
        assertThat(owner.includes(grants("viewer"))).isFalse();
        assertThat(grants("admin").includes(grants("admin:*", "author:prod/app", "viewer"))).isTrue();
    }

    @Test
    void ceilingsReadTheGroupsSyntaxAndWhatTheyAreWrittenAs() {
        assertThat(text(grants("admin", "viewer:prod", "runner:prod/app", "author:*"))).containsExactly(
            "admin:*", "viewer:prod", "runner:prod/app", "author:*");
        Grants stored = AccessTokenService.parseCeiling(AccessTokenService.formatCeiling(grants("admin", "viewer:prod")));
        assertThat(text(stored)).containsExactly("admin:*", "viewer:prod");
        assertThatThrownBy(() -> grants("owner:prod", "viewer:a/b/c"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("owner:prod").hasMessageContaining("viewer:a/b/c");
    }

    @Test
    void secretsAreHashedWithSha256() {
        assertThat(AccessTokenService.hash("sqlx_abc")).hasSize(64).matches("[0-9a-f]+")
            .isEqualTo(AccessTokenService.hash("sqlx_abc")).isNotEqualTo(AccessTokenService.hash("sqlx_abd"));
    }
}
