package com.github.safetytrick.sqlexplorer.auth;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code sql-explorer.auth.*}. The OIDC client itself (client id, endpoints,
 * scopes) is Spring Boot's own registration,
 * {@code spring.security.oauth2.client.registration.sql-explorer} and its
 * provider; these are only what Boot's properties cannot say.
 *
 * @param mode                  {@code none} (the default) or {@code oidc}; see {@link AuthMode}
 * @param issuer                the {@code iss} every ID token must name. Needed when
 *                              the provider's endpoints are given one by one rather
 *                              than discovered from {@code issuer-uri}, as they are
 *                              for a backend in a container (dev-idp/README.md,
 *                              "Which issuer"). Without it, and without
 *                              {@code issuer-uri}, Spring checks no issuer at all.
 * @param endSessionUri         the provider's RP-initiated logout endpoint, browser
 *                              facing. Discovery supplies it when {@code issuer-uri}
 *                              is used; otherwise sign-out is local only unless it
 *                              is set here.
 * @param postLogoutRedirectUri where the provider sends the browser after sign-out.
 *                              The default is the root of whatever address the app
 *                              was reached by, which is the UI.
 * @param groupPrefix           groups that name their grant directly start with this:
 *                              {@code sqlx:viewer:prod}, {@code sqlx:admin} (sqlx-e13.4)
 * @param groupGrants           groups the provider names its own way, mapped to the
 *                              grants they carry, for providers whose group names
 *                              cannot follow the prefix scheme:
 *                              {@code dba-team: [admin]}, {@code eng: [viewer:prod, runner:staging]}
 */
@ConfigurationProperties("sql-explorer.auth")
public record AuthProperties(
        @DefaultValue("none") AuthMode mode,
        String issuer,
        String endSessionUri,
        @DefaultValue("{baseScheme}://{baseHost}{basePort}/") String postLogoutRedirectUri,
        @DefaultValue("sqlx:") String groupPrefix,
        Map<String, List<String>> groupGrants) {

    public AuthProperties {
        groupGrants = groupGrants == null ? Map.of() : Map.copyOf(groupGrants);
    }

    public boolean oidc() {
        return mode == AuthMode.OIDC;
    }
}
