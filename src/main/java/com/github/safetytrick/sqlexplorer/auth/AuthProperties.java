package com.github.safetytrick.sqlexplorer.auth;

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
 */
@ConfigurationProperties("sql-explorer.auth")
public record AuthProperties(
        @DefaultValue("none") AuthMode mode,
        String issuer,
        String endSessionUri,
        @DefaultValue("{baseScheme}://{baseHost}{basePort}/") String postLogoutRedirectUri) {

    public boolean oidc() {
        return mode == AuthMode.OIDC;
    }
}
