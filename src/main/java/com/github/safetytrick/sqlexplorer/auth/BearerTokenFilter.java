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

final class BearerTokenFilter extends OncePerRequestFilter {

    static final Logger audit = LoggerFactory.getLogger("sql-explorer.audit");

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
