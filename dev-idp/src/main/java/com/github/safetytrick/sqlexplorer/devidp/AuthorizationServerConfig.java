package com.github.safetytrick.sqlexplorer.devidp;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

/**
 * Spring Authorization Server with the OIDC endpoints, one public client and
 * the canned users of {@link DevUsers}. Everything lives in memory and is lost
 * on restart, including the signing key.
 */
@Configuration(proxyBeanMethods = false)
public class AuthorizationServerConfig {

    /** The protocol endpoints: authorize, token, jwks, userinfo, discovery, logout. */
    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer = new OAuth2AuthorizationServerConfigurer();
        http.securityMatcher(authorizationServer.getEndpointsMatcher())
                .with(authorizationServer, as -> as
                        .oidc(oidc -> oidc.userInfoEndpoint(userInfo -> userInfo.userInfoMapper(AuthorizationServerConfig::userInfo))))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                // an unauthenticated browser on /oauth2/authorize goes to the login form
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                // userinfo is called with the access token as a bearer token
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()));
        return http.build();
    }

    /** Everything else, which is the login form. */
    @Bean
    @Order(2)
    SecurityFilterChain loginChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .formLogin(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    UserDetailsService users() {
        return new InMemoryUserDetailsManager(DevUsers.ALL.stream()
                .map(user -> User.withUsername(user.username())
                        .password("{noop}" + user.password())
                        .roles("USER")
                        .build())
                .toList());
    }

    @Bean
    RegisteredClientRepository clients(DevIdpProperties properties) {
        RegisteredClient sqlExplorer = RegisteredClient.withId(properties.clientId())
                .clientId(properties.clientId())
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUris(uris -> uris.addAll(properties.allRedirectUris()))
                .postLogoutRedirectUris(uris -> uris.addAll(properties.allPostLogoutRedirectUris()))
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(OidcScopes.EMAIL)
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .build();
        return new InMemoryRegisteredClientRepository(sqlExplorer);
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(DevIdpProperties properties) {
        return AuthorizationServerSettings.builder().issuer(properties.issuer()).build();
    }

    /** Puts the user's groups and profile into the ID token and the access token. */
    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> groupsClaim() {
        return context -> {
            boolean idToken = OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue());
            boolean accessToken = OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType());
            if (!idToken && !accessToken) {
                return;
            }
            DevUsers.find(context.getPrincipal().getName())
                    .ifPresent(user -> context.getClaims().claims(claims -> claims.putAll(profileClaims(user))));
        };
    }

    private static OidcUserInfo userInfo(OidcUserInfoAuthenticationContext context) {
        String subject = context.getAuthorization().getPrincipalName();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", subject);
        DevUsers.find(subject).ifPresent(user -> claims.putAll(profileClaims(user)));
        return new OidcUserInfo(claims);
    }

    /** {@code groups} is always present, as an empty list for a user with none. */
    private static Map<String, Object> profileClaims(DevUsers.DevUser user) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("preferred_username", user.username());
        claims.put("name", user.name());
        claims.put("email", user.email());
        claims.put("email_verified", true);
        claims.put("groups", user.groups());
        return claims;
    }

    /**
     * Loaded from {@code dev-idp.key-file}, or generated and written there on
     * first start, so tokens and sessions survive a restart. The file lives in
     * a git-ignored directory: a committed private key would be flagged, and
     * each developer's IdP signs with its own.
     */
    @Bean
    JWKSource<SecurityContext> jwkSource(DevIdpProperties properties) {
        return new ImmutableJWKSet<>(new JWKSet(SigningKeys.loadOrCreate(Path.of(properties.keyFile()))));
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }
}
