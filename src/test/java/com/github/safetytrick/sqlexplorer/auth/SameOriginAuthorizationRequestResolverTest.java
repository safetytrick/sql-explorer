package com.github.safetytrick.sqlexplorer.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import static org.assertj.core.api.Assertions.assertThat;

class SameOriginAuthorizationRequestResolverTest {

    private static final String API = "/api/sql-explorer";

    @Test
    void aRelativeAuthorizationUriIsResolvedAgainstTheOriginTheBrowserUsed() {
        OAuth2AuthorizationRequest request = resolve("/idp/oauth2/authorize", tunnel());

        assertThat(request.getAuthorizationRequestUri())
                .startsWith("https://tunnel.example.test/idp/oauth2/authorize?")
                .contains("redirect_uri=https://tunnel.example.test" + API + "/login/oauth2/code/sql-explorer");
    }

    @Test
    void theSameConfigurationOnLocalhostKeepsItsPort() {
        MockHttpServletRequest local = authorizationRequest();
        local.setServerName("localhost");
        local.setServerPort(4200);

        assertThat(resolve("/idp/oauth2/authorize", local).getAuthorizationRequestUri())
                .startsWith("http://localhost:4200/idp/oauth2/authorize?");
    }

    @Test
    void anAbsoluteAuthorizationUriIsLeftAlone() {
        assertThat(resolve("https://idp.example.test/oauth2/authorize", tunnel()).getAuthorizationRequestUri())
                .startsWith("https://idp.example.test/oauth2/authorize?");
    }

    @Test
    void aRequestThatIsNotForTheAuthorizationEndpointResolvesToNothing() {
        MockHttpServletRequest other = tunnel();
        other.setRequestURI(API + "/user");
        other.setServletPath("/user");
        assertThat(resolver("/idp/oauth2/authorize").resolve(other)).isNull();
    }

    private static OAuth2AuthorizationRequest resolve(String authorizationUri, MockHttpServletRequest request) {
        return resolver(authorizationUri).resolve(request);
    }

    private static SameOriginAuthorizationRequestResolver resolver(String authorizationUri) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("sql-explorer")
                .clientId("sql-explorer")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid")
                .authorizationUri(authorizationUri)
                .tokenUri("http://dev-idp:18190/idp/oauth2/token")
                .jwkSetUri("http://dev-idp:18190/idp/oauth2/jwks")
                .build();
        return new SameOriginAuthorizationRequestResolver(new InMemoryClientRegistrationRepository(registration));
    }

    /** As the app sees a browser on an https tunnel, after the forwarded headers are applied. */
    private static MockHttpServletRequest tunnel() {
        MockHttpServletRequest request = authorizationRequest();
        request.setScheme("https");
        request.setSecure(true);
        request.setServerName("tunnel.example.test");
        request.setServerPort(443);
        return request;
    }

    private static MockHttpServletRequest authorizationRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", API + "/oauth2/authorization/sql-explorer");
        request.setContextPath(API);
        request.setServletPath("/oauth2/authorization/sql-explorer");
        return request;
    }
}
