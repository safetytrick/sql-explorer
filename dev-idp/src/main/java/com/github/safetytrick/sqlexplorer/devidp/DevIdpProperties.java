package com.github.safetytrick.sqlexplorer.devidp;

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
 */
@ConfigurationProperties("dev-idp")
public record DevIdpProperties(
        String issuer,
        String clientId,
        List<String> redirectUris,
        List<String> postLogoutRedirectUris,
        String keyFile) {
}
