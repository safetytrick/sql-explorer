package com.github.safetytrick.sqlexplorer.devidp;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param issuer          the issuer every token names and discovery advertises.
 *                        Fixed rather than derived from each request, so the
 *                        {@code iss} claim is the same however the IdP was
 *                        reached (see README, "Which issuer").
 * @param clientId        the one registered client, public (no secret, PKCE)
 * @param redirectUris    where the client may be sent back with a code; exact match
 * @param postLogoutRedirectUris where RP-initiated logout may return to
 * @param keyFile         the private signing JWK; generated there on first start
 * @param extraOrigins    more origins sql-explorer is reached at, such as a
 *                        tunnel's {@code https://…}: each adds that origin's
 *                        sign-in callback and its root as a post-logout target
 * @param rememberFor     how long a sign-in is remembered, across idle sessions
 *                        and restarts; {@code 0} asks for the password whenever
 *                        the session is gone. Default 90 days.
 */
@ConfigurationProperties("dev-idp")
public record DevIdpProperties(
        String issuer,
        String clientId,
        List<String> redirectUris,
        List<String> postLogoutRedirectUris,
        String keyFile,
        List<String> extraOrigins,
        Duration rememberFor) {

    /** sql-explorer's oauth2Login callback, under its servlet context path. */
    static final String CALLBACK_PATH = "/api/sql-explorer/login/oauth2/code/sql-explorer";

    public DevIdpProperties {
        rememberFor = rememberFor == null || rememberFor.isNegative() ? Duration.ofDays(90) : rememberFor;
        extraOrigins = extraOrigins == null ? List.of()
                : extraOrigins.stream().map(String::trim).filter(o -> !o.isEmpty())
                        .map(o -> o.endsWith("/") ? o.substring(0, o.length() - 1) : o).toList();
    }

    List<String> allRedirectUris() {
        return concat(redirectUris, extraOrigins.stream().map(o -> o + CALLBACK_PATH).toList());
    }

    List<String> allPostLogoutRedirectUris() {
        return concat(postLogoutRedirectUris, extraOrigins.stream().map(o -> o + "/").toList());
    }

    private static List<String> concat(List<String> configured, List<String> extra) {
        return java.util.stream.Stream.concat((configured == null ? List.<String>of() : configured).stream(), extra.stream())
                .distinct().toList();
    }
}
