package com.github.safetytrick.sqlexplorer.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientPropertiesMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.util.function.SingletonSupplier;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sign-in (sqlx-e13.1). Two modes, {@code sql-explorer.auth.mode}:
 *
 * <p><b>{@code none}</b>, the default for now: everything is open, as it was,
 * and every request is the legacy {@code admin} user. A warning at startup and
 * a banner in the UI say so.
 *
 * <p><b>{@code oidc}</b>: Spring's {@code oauth2Login} against the client
 * registration named {@value #REGISTRATION}, a public client with PKCE.
 * <ul>
 *   <li>The API answers <b>401 JSON</b> without a session, never a redirect:
 *       an XHR cannot follow one to a login page. The UI turns that 401 into a
 *       navigation to {@code /login}, which does redirect to the provider.</li>
 *   <li>CSRF uses Spring's cookie token, which Angular's HttpClient reads from
 *       {@code XSRF-TOKEN} and echoes as {@code X-XSRF-TOKEN}. The cookie's
 *       path is {@code /}: the UI's pages are not under the API's context path,
 *       and a page can only read cookies whose path covers its own.</li>
 *   <li>Sign-out is a POST to {@code /logout}, answered with JSON naming where
 *       to go next: the provider's end-session endpoint (RP-initiated logout)
 *       where one is known, else the UI's root.</li>
 *   <li><b>{@code /mcp} is open, without a session and without CSRF.</b> MCP
 *       clients cannot do a browser sign-in, and personal access tokens are
 *       sqlx-e13.5. Until then anything that can reach the endpoint can use
 *       it, as before, and what it writes is attributed to {@code admin}. Its
 *       own Origin check (McpServerConfig) still refuses browser pages from
 *       other sites.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties({AuthProperties.class, OAuth2ClientProperties.class})
class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** The client registration sign-in uses: {@code spring.security.oauth2.client.registration.sql-explorer}. */
    static final String REGISTRATION = "sql-explorer";

    /** Open without a session even in {@code oidc} mode. */
    private static final String[] OPEN = {
        LoginResource.PATH, "/error",
        // MCP clients cannot sign in; tokens are sqlx-e13.5
        "/mcp",
        // liveness and scraping, as before
        "/actuator/health", "/actuator/health/**", "/actuator/prometheus",
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthProperties auth, JsonMapper json,
                                            ObjectProvider<ClientRegistrationRepository> registrations,
                                            UserService users) throws Exception {
        // never save an API request to "return to" after sign-in; /login says where to go
        http.requestCache(cache -> cache.requestCache(new NullRequestCache()));
        if (!auth.oidc()) {
            return http
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .build();
        }
        ClientRegistrationRepository repository = registrations.getObject();
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookieCustomizer(cookie -> cookie.path("/"));
        return http
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(OPEN).permitAll()
                .anyRequest().authenticated())
            .oauth2Login(login -> login
                .loginPage(LoginResource.PATH)
                .clientRegistrationRepository(repository)
                .successHandler(signedIn(users))
                .failureHandler(signInFailed()))
            .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(unauthorized(json)))
            .csrf(csrf -> csrf
                .spa()
                .csrfTokenRepository(csrfTokens)
                .ignoringRequestMatchers("/mcp"))
            .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
            .logout(logout -> logout.logoutSuccessHandler(signedOut(repository, auth, json)))
            .build();
    }

    /** 401 with a JSON body that says where to sign in. */
    private static AuthenticationEntryPoint unauthorized(JsonMapper json) {
        return (request, response, exception) -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", HttpStatus.UNAUTHORIZED.value());
            body.put("error", "Unauthorized");
            body.put("message", "Sign in to continue.");
            body.put("login", request.getContextPath() + LoginResource.PATH);
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            json.writeValue(response.getWriter(), body);
        };
    }

    /** Records the sign-in, then goes back to the page the user was on. */
    private static AuthenticationSuccessHandler signedIn(UserService users) {
        return (request, response, authentication) -> {
            if (authentication.getPrincipal() instanceof OidcUser oidc) {
                users.signedIn(oidc);
            }
            // relative to the server root, not the API's context path: it is a page of the UI
            response.sendRedirect(LoginResource.takeReturn(request));
        };
    }

    /**
     * A failed sign-in stops here rather than going back to {@code /login},
     * which would send the browser straight back to the provider, and round
     * again for as long as the failure lasts.
     */
    private static AuthenticationFailureHandler signInFailed() {
        return (request, response, exception) -> {
            log.warn("sign-in failed: {}", exception.getMessage());
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            // plain text, so nothing in the message can be markup
            response.setContentType(MediaType.TEXT_PLAIN_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().println("Sign-in failed: " + exception.getMessage());
            response.getWriter().println();
            response.getWriter().println("Try again: " + request.getContextPath() + LoginResource.PATH);
        };
    }

    /**
     * RP-initiated logout where the provider has an end-session endpoint. The
     * UI signs out with an XHR, which cannot follow a redirect to another
     * site, so the target is written as {@code {"location": ...}} for the UI
     * to navigate to.
     */
    private static OidcClientInitiatedLogoutSuccessHandler signedOut(ClientRegistrationRepository repository,
                                                                     AuthProperties auth, JsonMapper json) {
        OidcClientInitiatedLogoutSuccessHandler handler = new OidcClientInitiatedLogoutSuccessHandler(repository);
        handler.setPostLogoutRedirectUri(auth.postLogoutRedirectUri());
        handler.setDefaultTargetUrl("/");
        handler.setRedirectStrategy((request, response, url) -> {
            response.setStatus(HttpStatus.OK.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            json.writeValue(response.getWriter(), Map.of("location", url));
        });
        return handler;
    }

    /**
     * The registration, built when first needed rather than at startup. With
     * {@code issuer-uri} Boot would fetch the provider's discovery document
     * while the context starts, and startup must not depend on reaching
     * anything; a provider that is down fails the sign-in, not the app.
     */
    @Bean
    @ConditionalOnProperty(name = "sql-explorer.auth.mode", havingValue = "oidc")
    ClientRegistrationRepository clientRegistrationRepository(OAuth2ClientProperties properties, AuthProperties auth) {
        if (!properties.getRegistration().containsKey(REGISTRATION)) {
            throw new IllegalStateException("sql-explorer.auth.mode is oidc but no client is configured: set "
                + "spring.security.oauth2.client.registration." + REGISTRATION + ".* and its provider "
                + "(see dev-idp/README.md), or set sql-explorer.auth.mode to none");
        }
        SingletonSupplier<ClientRegistration> registration = SingletonSupplier.of(() -> {
            ClientRegistration configured = new OAuth2ClientPropertiesMapper(properties).asClientRegistrations().get(REGISTRATION);
            ClientRegistration.Builder builder = ClientRegistration.withClientRegistration(configured);
            if (auth.issuer() != null && !auth.issuer().isBlank()) {
                builder.issuerUri(auth.issuer());
            }
            if (auth.endSessionUri() != null && !auth.endSessionUri().isBlank()) {
                Map<String, Object> metadata = new LinkedHashMap<>(configured.getProviderDetails().getConfigurationMetadata());
                metadata.put("end_session_endpoint", auth.endSessionUri());
                builder.providerConfigurationMetadata(metadata);
            }
            return builder.build();
        });
        return id -> REGISTRATION.equals(id) ? registration.obtain() : null;
    }

    @EventListener(ApplicationReadyEvent.class)
    void announce(ApplicationReadyEvent event) {
        AuthProperties auth = event.getApplicationContext().getBean(AuthProperties.class);
        if (auth.oidc()) {
            log.info("Sign-in is on (sql-explorer.auth.mode=oidc), through client registration '{}'", REGISTRATION);
            return;
        }
        boolean configured = event.getApplicationContext().getEnvironment()
            .containsProperty("spring.security.oauth2.client.registration." + REGISTRATION + ".client-id");
        log.warn("Sign-in is NOT configured (sql-explorer.auth.mode=none): anyone who can reach this server can "
            + "use it, and everything is recorded as the legacy '{}' user.{}", UserService.LEGACY_ADMIN,
            configured ? " A '" + REGISTRATION + "' client is configured but unused; set sql-explorer.auth.mode=oidc to use it." : "");
    }

    /**
     * Loads the CSRF token on every request so its cookie is written before
     * the UI's first POST. Spring defers the token until something reads it,
     * and nothing in a JSON API does.
     */
    private static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
