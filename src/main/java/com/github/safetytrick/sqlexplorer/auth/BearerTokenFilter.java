package com.github.safetytrick.sqlexplorer.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code Authorization: Bearer sqlx_…} on any request (sqlx-e13.5).
 *
 * <p>A valid token replaces whatever else the request carried for this
 * request only: the context is set on the thread and never saved to a session,
 * so a bearer request is stateless and a cookie sent beside it changes nothing.
 * An invalid, expired or revoked token is a 401 here and goes no further; it
 * does not fall back to a session, so a client never silently acts as someone
 * else.
 *
 * <p>One place a token cannot go, refused here with a message that says why:
 * {@code /tokens}, so a token cannot make tokens, nor widen or extend itself.
 * SecurityConfig and AccessTokenResource refuse it again, in case a path ever
 * reaches the resource in a form this check does not recognise.
 *
 * <p>Every request a token makes that is not a plain read is written to the
 * {@code sql-explorer.audit} log with the owner and the token's id. The rows
 * it writes name the owner as their author ({@link UserService#currentUser()});
 * this line is what says a token did it, until audit records carry the token
 * themselves (sqlx-tev).
 */
final class BearerTokenFilter extends OncePerRequestFilter {

    static final Logger audit = LoggerFactory.getLogger("sql-explorer.audit");

    /** Put on the MDC while a token's request runs, so its log lines say which token. */
    static final String MDC_KEY = "accessToken";

    private static final String BEARER = "bearer ";
    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");

    private final AccessTokenService tokens;
    private final JsonMapper json;
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

    BearerTokenFilter(AccessTokenService tokens, JsonMapper json) {
        this.tokens = tokens;
        this.json = json;
    }

    /** Whether the request presents a bearer token at all, valid or not. */
    static boolean presentsBearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.length() > BEARER.length()
            && header.regionMatches(true, 0, BEARER, 0, BEARER.length());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!presentsBearer(request)) {
            chain.doFilter(request, response);
            return;
        }
        // decoded, and without the context path: what the servlet will be asked for
        String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
        if (path.equals("/tokens") || path.startsWith("/tokens/")) {
            write(response, HttpStatus.FORBIDDEN, "An access token cannot manage access tokens. "
                + "Sign in with a browser to make or revoke one.", false);
            return;
        }
        String secret = request.getHeader(HttpHeaders.AUTHORIZATION).substring(BEARER.length()).strip();
        AccessTokenService.Presented presented = tokens.authenticate(secret);
        if (presented.authentication() == null) {
            write(response, HttpStatus.UNAUTHORIZED, presented.refusal().message(), true);
            return;
        }
        AccessTokenAuthentication authentication = presented.authentication();
        SecurityContext context = contexts.createEmptyContext();
        context.setAuthentication(authentication);
        contexts.setContext(context);
        MDC.put(MDC_KEY, String.valueOf(authentication.getPrincipal().tokenId()));
        try {
            if (!READS.contains(request.getMethod().toUpperCase(Locale.ROOT)) && !path.equals("/mcp")) {
                // /mcp is always a POST; McpToolRunner logs the tool calls that write
                audit.info("token request: {} {} by {}", request.getMethod(), path, authentication.getPrincipal());
            }
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            contexts.clearContext();
        }
    }

    private void write(HttpServletResponse response, HttpStatus status, String message, boolean challenge) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        response.setStatus(status.value());
        if (challenge) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(response.getWriter(), body);
    }
}
