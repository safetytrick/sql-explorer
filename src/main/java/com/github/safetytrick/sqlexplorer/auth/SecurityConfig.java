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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.util.function.SingletonSupplier;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableConfigurationProperties({AuthProperties.class, OAuth2ClientProperties.class})
class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    static final String REGISTRATION = "sql-explorer";

    private static final String[] OPEN = {
        LoginResource.PATH, "/error",
        "/actuator/health", "/actuator/health/**", "/actuator/prometheus",
    };

    private static final String[] READ_POSTS = {
        "/env/{env}/queries/search", "/env/{env}/graph/v2/search", "/env/{env}/tags/search/preview",
        "/env/{env}/tags/path", "/env/{env}/tags/targets", "/env/{env}/notes/paths",
        "/env/{env}/annotations/targets", "/env/{env}/annotations/history",
        "/env/{env}/query-examples", "/env/{env}/ddl/preflight", "/env/{env}/unused-indexes/read",
        "/env/{env}/topology/compare",
    };

    private static AuthorizationManager<RequestAuthorizationContext> may(Permission permission, Authorizations authorizations) {
        return (authentication, context) -> {
            String env = context.getVariables().get("env");
            return new AuthorizationDecision(env != null && authorizations.of(authentication.get()).allows(permission, env));
        };
    }

    private static AuthorizationManager<RequestAuthorizationContext> signedInOrVisitor(GroupGrants groupGrants) {
        boolean visitors = !groupGrants.anonymous().grants().isEmpty();
        return (authentication, context) -> {
            Authentication who = authentication.get();
            boolean signedIn = who != null && who.isAuthenticated() && !(who instanceof AnonymousAuthenticationToken);
            return new AuthorizationDecision(signedIn || visitors);
        };
    }

    private static AuthorizationManager<RequestAuthorizationContext> admin(Authorizations authorizations) {
        return (authentication, context) -> {
            Authentication who = authentication.get();
            boolean signedIn = who != null && who.isAuthenticated() && !(who instanceof AnonymousAuthenticationToken);
            return new AuthorizationDecision(signedIn && authorizations.of(who).admin());
        };
    }

    private static AuthorizationManager<RequestAuthorizationContext> browserSession() {
        return (authentication, context) -> {
            Authentication who = authentication.get();
            return new AuthorizationDecision(who != null && who.isAuthenticated() && who.getPrincipal() instanceof OidcUser);
        };
    }

    private static AccessDeniedHandler forbidden(JsonMapper json) {
        AuthenticationEntryPoint unauthorized = unauthorized(json);
        return (request, response, exception) -> {
            Authentication who = SecurityContextHolder.getContext().getAuthentication();
            if (exception instanceof CsrfException
                    && (who == null || !who.isAuthenticated() || who instanceof AnonymousAuthenticationToken)) {
                unauthorized.commence(request, response, new InsufficientAuthenticationException("not signed in"));
                return;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", HttpStatus.FORBIDDEN.value());
            body.put("error", "Forbidden");
            body.put("message", "You do not have access to this. Ask an administrator for the group that grants it.");
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            json.writeValue(response.getWriter(), body);
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthProperties auth, JsonMapper json,
                                            ObjectProvider<ClientRegistrationRepository> registrations,
                                            UserService users, Authorizations authorizations, GroupGrants groupGrants,
                                            AccessTokenService accessTokens) throws Exception {
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
        // a page can only read cookies whose path covers its own, and the UI is not under the context path
        csrfTokens.setCookieCustomizer(cookie -> cookie.path("/"));
        return http
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(OPEN).permitAll()
                .requestMatchers(HttpMethod.POST, "/environments/{env}/pull", "/environments/{env}/refresh-schema")
                    .access(may(Permission.ENV_PULL, authorizations))
                .requestMatchers("/tokens", "/tokens/**").access(browserSession())
                .requestMatchers("/mcp").authenticated()
                .requestMatchers(HttpMethod.GET, "/environments", "/user", "/graph/layout").access(signedInOrVisitor(groupGrants))
                .requestMatchers("/util/**").access(signedInOrVisitor(groupGrants))
                .requestMatchers("/graph/layout/**").authenticated()
                .requestMatchers(HttpMethod.POST, "/env/{env}/sources", "/env/{env}/sources/load",
                        "/env/{env}/environments/**").access(admin(authorizations))
                .requestMatchers(HttpMethod.POST, "/env/{env}/query-examples/*/explain", "/env/{env}/analyzed-sql/*/explain")
                    .access(may(Permission.ENV_EXPLAIN, authorizations))
                .requestMatchers(HttpMethod.POST, "/env/{env}/profile/**").access(may(Permission.ENV_SAMPLE, authorizations))
                .requestMatchers(HttpMethod.POST, READ_POSTS).access(may(Permission.EXPLORER_READ, authorizations))
                .requestMatchers(HttpMethod.GET, "/env/{env}/**").access(may(Permission.EXPLORER_READ, authorizations))
                .requestMatchers("/env/{env}/**").access(may(Permission.COMMENTARY_WRITE, authorizations))
                .anyRequest().access(admin(authorizations)))
            .oauth2Login(login -> login
                .loginPage(LoginResource.PATH)
                .clientRegistrationRepository(repository)
                .authorizationEndpoint(endpoint -> endpoint
                    .authorizationRequestResolver(new SameOriginAuthorizationRequestResolver(repository)))
                .successHandler(signedIn(users))
                .failureHandler(signInFailed()))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(unauthorized(json))
                .accessDeniedHandler(forbidden(json)))
            .csrf(csrf -> csrf
                .spa()
                .csrfTokenRepository(csrfTokens)
                // a bearer request carries no cookie to forge: a page on another site cannot set the
                // header without a CORS preflight, which is not granted. Sessions keep CSRF, /mcp included.
                .ignoringRequestMatchers(BearerTokenFilter::presentsBearer))
            .addFilterBefore(new BearerTokenFilter(accessTokens, json), CsrfFilter.class)
            .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
            .logout(logout -> logout.logoutSuccessHandler(signedOut(repository, auth, json)))
            .build();
    }

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

    private static AuthenticationSuccessHandler signedIn(UserService users) {
        return (request, response, authentication) -> {
            if (authentication.getPrincipal() instanceof OidcUser oidc) {
                users.signedIn(oidc);
            }
            response.sendRedirect(LoginResource.takeReturn(request));
        };
    }

    private static AuthenticationFailureHandler signInFailed() {
        return (request, response, exception) -> {
            log.warn("sign-in failed: {}", exception.getMessage());
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.TEXT_PLAIN_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().println("Sign-in failed: " + exception.getMessage());
            response.getWriter().println();
            response.getWriter().println("Try again: " + request.getContextPath() + LoginResource.PATH);
        };
    }

    /**
     * An XHR cannot follow a redirect to another site, so the end-session target
     * is written as {@code {"location": ...}} for the UI to navigate to.
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
     * Built when first needed: with {@code issuer-uri} Boot would fetch the
     * provider's discovery document while the context starts.
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
     * Spring defers the CSRF token until something reads it, and nothing in a
     * JSON API does, so the cookie would never be written before the first POST.
     */
    private static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (!BearerTokenFilter.presentsBearer(request)
                    && request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
