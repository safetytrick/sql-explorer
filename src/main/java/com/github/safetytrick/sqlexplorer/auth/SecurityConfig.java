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
 *   <li><b>Authorization is by URL</b> (sqlx-e13.4): the environment is in the
 *       path ({@code /env/{env}/…}), and each rule asks whether the caller's
 *       grants ({@link GroupGrants}) allow one {@link Permission} there. Reads
 *       need {@code explorer:read}, other writes {@code commentary:write},
 *       EXPLAIN and profiling the Runner permissions, pulls and loads Admin.
 *       Anything not classified is Admin's, so a new endpoint is closed until
 *       someone decides otherwise.</li>
 *   <li><b>Visitors</b> (sqlx-2lc): with {@code sql-explorer.auth.anonymous}
 *       set, a request without a session reads what it grants, as
 *       {@link Role#READER} at most. Anything more answers 401, so the UI
 *       sends them to sign in; the nav offers a Sign in link meanwhile.</li>
 *   <li>Sign-out is a POST to {@code /logout}, answered with JSON naming where
 *       to go next: the provider's end-session endpoint (RP-initiated logout)
 *       where one is known, else the UI's root.</li>
 *   <li><b>Personal access tokens</b> (sqlx-e13.5): {@code Authorization:
 *       Bearer sqlx_…} on any request, checked by {@link BearerTokenFilter}.
 *       Stateless and without CSRF; it may do what both its owner and its
 *       ceiling allow ({@link AccessTokenService}), and the same URL rules
 *       decide. A bad token is a 401, never a fall back to a session.
 *       {@code /tokens} itself takes only a browser session.</li>
 *   <li><b>{@code /mcp} needs a token</b> (or a session, which is how the MCP
 *       page lists the tools; with CSRF like any other session request). Each
 *       tool checks the permission it needs (McpToolRunner). With sign-in off
 *       it is open, as everything is.</li>
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
        // liveness and scraping, as before
        "/actuator/health", "/actuator/health/**", "/actuator/prometheus",
    };

    /**
     * POSTs under {@code /env/{env}/} that read rather than write: searches and
     * batched lookups whose request is a body. They need only
     * {@code explorer:read}; every other POST there is commentary.
     */
    private static final String[] READ_POSTS = {
        "/env/{env}/queries/search", "/env/{env}/graph/v2/search", "/env/{env}/tags/search/preview",
        "/env/{env}/tags/path", "/env/{env}/tags/targets", "/env/{env}/notes/paths",
        "/env/{env}/annotations/targets", "/env/{env}/annotations/history",
        "/env/{env}/query-examples", "/env/{env}/ddl/preflight", "/env/{env}/unused-indexes/read",
        // a workbench search compared across servers; a reader without commentary was refused it (sqlx-387)
        "/env/{env}/topology/compare",
    };

    /**
     * Allowed when the caller's grants allow {@code permission} on the
     * environment named by the path's {@code {env}} (sqlx-e13.4). The scope is
     * read from the URL alone, which is why the environment is in it
     * (sqlx-e13.6).
     */
    private static AuthorizationManager<RequestAuthorizationContext> may(Permission permission, Authorizations authorizations) {
        return (authentication, context) -> {
            String env = context.getVariables().get("env");
            // not signed in, the grants are sql-explorer.auth.anonymous's, reading at most (sqlx-2lc);
            // refused, a visitor gets the 401 that sends the UI to sign in, not a 403
            return new AuthorizationDecision(env != null && authorizations.of(authentication.get()).allows(permission, env));
        };
    }

    /**
     * Signed in, or a visitor when visitors may read something (sqlx-2lc): for
     * the global reads every page makes, {@code /user} and {@code /environments}
     * among them. Without anonymous grants this is {@code authenticated()}, and
     * the 401 on {@code /user} is what sends a visitor to sign in.
     */
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

    /** Signed in through the browser: a session, never an access token (sqlx-e13.5). */
    private static AuthorizationManager<RequestAuthorizationContext> browserSession() {
        return (authentication, context) -> {
            Authentication who = authentication.get();
            return new AuthorizationDecision(who != null && who.isAuthenticated() && who.getPrincipal() instanceof OidcUser);
        };
    }

    /**
     * 403 with a JSON body, so the UI can say what is missing rather than show
     * nothing. A write refused for its missing CSRF token by someone not
     * signed in at all is a 401 instead: CSRF is checked before anything else,
     * and "forbidden" would tell an MCP client that forgot its token the wrong
     * thing.
     */
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
                // ── global ──────────────────────────────────────────────────
                .requestMatchers(HttpMethod.POST, "/environments/{env}/pull", "/environments/{env}/refresh-schema")
                    .access(may(Permission.ENV_PULL, authorizations))
                // a person's own tokens, from a browser session only: a token never makes a token
                .requestMatchers("/tokens", "/tokens/**").access(browserSession())
                // MCP: a token, or a session (the MCP page lists the tools); each tool checks its own permission
                .requestMatchers("/mcp").authenticated()
                // global reads, and the SQL utilities, which are pure functions of their arguments
                .requestMatchers(HttpMethod.GET, "/environments", "/user", "/graph/layout").access(signedInOrVisitor(groupGrants))
                .requestMatchers("/util/**").access(signedInOrVisitor(groupGrants))
                .requestMatchers("/graph/layout/**").authenticated()
                // ── one environment: /env/{env}/… ───────────────────────────
                // loading sources rewrites the environment, as a pull does
                .requestMatchers(HttpMethod.POST, "/env/{env}/sources", "/env/{env}/sources/load",
                        "/env/{env}/environments/**").access(admin(authorizations))
                .requestMatchers(HttpMethod.POST, "/env/{env}/query-examples/*/explain", "/env/{env}/analyzed-sql/*/explain")
                    .access(may(Permission.ENV_EXPLAIN, authorizations))
                .requestMatchers(HttpMethod.POST, "/env/{env}/profile/**").access(may(Permission.ENV_SAMPLE, authorizations))
                // POSTs that only ask a question with a body too large for a query string
                .requestMatchers(HttpMethod.POST, READ_POSTS).access(may(Permission.EXPLORER_READ, authorizations))
                .requestMatchers(HttpMethod.GET, "/env/{env}/**").access(may(Permission.EXPLORER_READ, authorizations))
                // every other write in an environment is commentary: tags, notes, annotations
                .requestMatchers("/env/{env}/**").access(may(Permission.COMMENTARY_WRITE, authorizations))
                // anything not named above is an operator's: a new endpoint is closed until it is classified
                .anyRequest().access(admin(authorizations)))
            .oauth2Login(login -> login
                .loginPage(LoginResource.PATH)
                .clientRegistrationRepository(repository)
                // a root-relative authorization-uri is on whatever origin the browser used (dev-idp behind nginx)
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
            if (!BearerTokenFilter.presentsBearer(request)
                    && request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
