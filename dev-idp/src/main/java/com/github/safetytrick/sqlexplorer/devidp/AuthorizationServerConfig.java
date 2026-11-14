package com.github.safetytrick.sqlexplorer.devidp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
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
import org.springframework.security.oauth2.server.authorization.oidc.web.authentication.OidcLogoutAuthenticationSuccessHandler;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.authentication.RememberMeAuthenticationProvider;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.authentication.NullRememberMeServices;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.logout.CompositeLogoutHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

/**
 * Spring Authorization Server with the OIDC endpoints, one public client and
 * the canned users of {@link DevUsers}. Sessions and codes live in memory;
 * the signing key is kept in {@code dev-idp.key-file}, and a sign-in is
 * remembered in a cookie for {@code dev-idp.remember-for}, so neither a
 * restart nor an idle session asks for the password again.
 */
@Configuration(proxyBeanMethods = false)
public class AuthorizationServerConfig {

    /** The protocol endpoints: authorize, token, jwks, userinfo, discovery, logout. */
    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerChain(HttpSecurity http, RememberMeServices rememberMe) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer = new OAuth2AuthorizationServerConfigurer();
        // signing out ends the remembered sign-in too, or the next sign-in
        // would come straight back as the same user
        OidcLogoutAuthenticationSuccessHandler signedOut = new OidcLogoutAuthenticationSuccessHandler();
        signedOut.setLogoutHandler(rememberMe instanceof LogoutHandler forget
                ? new CompositeLogoutHandler(new SecurityContextLogoutHandler(), forget)
                : new SecurityContextLogoutHandler());
        http.securityMatcher(authorizationServer.getEndpointsMatcher())
                .with(authorizationServer, as -> as
                        .oidc(oidc -> oidc
                                .userInfoEndpoint(userInfo -> userInfo.userInfoMapper(AuthorizationServerConfig::userInfo))
                                .logoutEndpoint(logout -> logout.logoutResponseHandler(signedOut))))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                // an unauthenticated browser on /oauth2/authorize goes to the login form
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                // userinfo is called with the access token as a bearer token
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()));
        // A remembered sign-in is honoured on /oauth2/authorize itself, which
        // otherwise sends the browser to the login form. Added by hand: the
        // rememberMe() configurer places its filter after the authorization
        // endpoint, which would then see no one signed in.
        if (rememberMe instanceof TokenBasedRememberMeServices services) {
            RememberMeAuthenticationFilter remembered = new RememberMeAuthenticationFilter(
                    new ProviderManager(new RememberMeAuthenticationProvider(services.getKey())), services);
            remembered.setSecurityContextRepository(new HttpSessionSecurityContextRepository());
            // the authorization endpoint sits right after the pre-authenticated slot
            http.addFilterBefore(remembered, AbstractPreAuthenticatedProcessingFilter.class);
        }
        return http.build();
    }

    /** Everything else, which is the login form. */
    @Bean
    @Order(2)
    SecurityFilterChain loginChain(HttpSecurity http, RememberMeServices rememberMe) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .formLogin(Customizer.withDefaults())
                .rememberMe(remember -> remember.rememberMeServices(rememberMe));
        return http.build();
    }

    /**
     * Every sign-in is remembered, with no checkbox: this IdP exists to be
     * signed in to. The cookie is a signed hash of user, expiry and password,
     * keyed from the signing key, so it survives a restart and needs nothing
     * stored. {@code remember-for: 0} turns it off.
     */
    @Bean
    RememberMeServices rememberMe(DevIdpProperties properties, UserDetailsService users, RSAKey signingKey) {
        if (properties.rememberFor().isZero()) {
            return new NullRememberMeServices();
        }
        TokenBasedRememberMeServices services = new RememberedSignIn(rememberMeKey(signingKey), users);
        services.setCookieName(REMEMBER_ME_COOKIE);
        services.setAlwaysRemember(true);
        services.setTokenValiditySeconds((int) Math.min(Integer.MAX_VALUE, properties.rememberFor().toSeconds()));
        return services;
    }

    /**
     * A remembered sign-in, carrying the time it was honoured as a factor. The
     * ID token's {@code auth_time} is taken from a factor authority, which a
     * password sign-in has and a remember-me one otherwise lacks; without it
     * the token endpoint fails. The cookie does not record when the password
     * was given, so this is when the browser was recognised, which is also
     * when sql-explorer's session starts.
     */
    static final class RememberedSignIn extends TokenBasedRememberMeServices {

        static final String FACTOR = "REMEMBER_ME";

        RememberedSignIn(String key, UserDetailsService users) {
            super(key, users);
        }

        @Override
        protected Authentication createSuccessfulAuthentication(HttpServletRequest request, UserDetails user) {
            List<GrantedAuthority> authorities = new ArrayList<>(user.getAuthorities());
            authorities.add(FactorGrantedAuthority.withFactor(FACTOR).issuedAt(Instant.now()).build());
            RememberMeAuthenticationToken authentication = new RememberMeAuthenticationToken(getKey(), user, authorities);
            authentication.setDetails(getAuthenticationDetailsSource().buildDetails(request));
            return authentication;
        }
    }

    /** Named apart from sql-explorer's cookies, as the session cookie is (application.yml). */
    static final String REMEMBER_ME_COOKIE = "DEV_IDP_REMEMBER";

    private static String rememberMeKey(RSAKey signingKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("remember-me:" + signingKey.toJSONString()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
    RSAKey signingKey(DevIdpProperties properties) {
        return SigningKeys.loadOrCreate(Path.of(properties.keyFile()));
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(RSAKey signingKey) {
        return new ImmutableJWKSet<>(new JWKSet(signingKey));
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }
}
