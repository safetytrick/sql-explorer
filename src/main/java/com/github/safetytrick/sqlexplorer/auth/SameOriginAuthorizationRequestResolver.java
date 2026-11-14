package com.github.safetytrick.sqlexplorer.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.util.UrlUtils;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Lets the provider's {@code authorization-uri} be root-relative
 * ({@code /idp/oauth2/authorize}), for a provider served on the app's own
 * origin behind the same proxy. It is resolved against the origin the browser
 * used, so one configuration works however the app is reached: localhost, or
 * a tunnel with its own hostname and scheme.
 *
 * <p>Needed because Spring's redirect would otherwise put the servlet context
 * path in front of a relative URL ({@code /api/sql-explorer/idp/…}). An
 * absolute {@code authorization-uri} passes through unchanged.
 *
 * <p>The origin is the request's scheme, host and port, which are the
 * forwarded ones behind a proxy ({@code server.forward-headers-strategy}).
 */
final class SameOriginAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private final OAuth2AuthorizationRequestResolver delegate;

    SameOriginAuthorizationRequestResolver(ClientRegistrationRepository repository) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(repository,
                OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return onOrigin(request, delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return onOrigin(request, delegate.resolve(request, clientRegistrationId));
    }

    private static OAuth2AuthorizationRequest onOrigin(HttpServletRequest request, OAuth2AuthorizationRequest resolved) {
        if (resolved == null || !resolved.getAuthorizationRequestUri().startsWith("/")) {
            return resolved;
        }
        String origin = UriComponentsBuilder.fromUriString(UrlUtils.buildFullRequestUrl(request))
                .replacePath(null).replaceQuery(null).fragment(null).build().toUriString();
        return OAuth2AuthorizationRequest.from(resolved)
                .authorizationRequestUri(origin + resolved.getAuthorizationRequestUri())
                .build();
    }
}
