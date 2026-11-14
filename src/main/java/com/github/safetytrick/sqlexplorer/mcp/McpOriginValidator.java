package com.github.safetytrick.sqlexplorer.mcp;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Which browser pages may call {@code /mcp}. MCP clients send no Origin and
 * are not affected; only a browser sends one.
 *
 * <p>A page served from this machine is always accepted. With sign-in off that
 * is all: anyone who reaches the endpoint can use it, so a page on another site
 * must not reach it through someone's localhost (DNS rebinding). Comparing the
 * Origin with the request's own address would not stop that, since a rebound
 * name is the same origin as itself.
 *
 * <p>With sign-in on, a page served from the address the request came in on is
 * accepted too, which is how the MCP page checks the endpoint when it is opened
 * through a tunnel. Rebinding gains nothing there: the rebound name holds no
 * session cookie, and a session request needs the CSRF token besides.
 */
final class McpOriginValidator implements ServerTransportSecurityValidator {

    /** This machine, any port. */
    static final List<String> LOCAL_ORIGINS = List.of(
        "http://localhost:*", "http://127.0.0.1:*", "http://[::1]:*");

    /**
     * The request's own address, put there by {@link OwnOrigin}. The SDK hands
     * a validator headers only, and by then Tomcat has consumed the proxy's
     * X-Forwarded-Proto into the request's scheme, so the headers alone would
     * read a tunnel's https as http.
     */
    static final String OWN_ORIGIN_HEADER = "X-Sql-Explorer-Own-Origin";

    private final ServerTransportSecurityValidator local =
        DefaultServerTransportSecurityValidator.builder().allowedOrigins(LOCAL_ORIGINS).build();
    private final boolean signInOn;

    McpOriginValidator(boolean signInOn) {
        this.signInOn = signInOn;
    }

    @Override
    public void validateHeaders(Map<String, List<String>> headers) throws ServerTransportSecurityException {
        String origin = first(headers, "Origin");
        if (signInOn && origin != null && origin.equalsIgnoreCase(first(headers, OWN_ORIGIN_HEADER))) {
            return;
        }
        local.validateHeaders(headers);
    }

    /** A header's first value, its name matched in any case. */
    private static String first(Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream()
            .filter(entry -> entry.getKey() != null && entry.getKey().equalsIgnoreCase(name))
            .flatMap(entry -> entry.getValue().stream())
            .findFirst().orElse(null);
    }

    /** {@code scheme://host[:port]} as a browser writes an Origin, a default port left out. */
    static String originOf(String scheme, String host, int port) {
        boolean defaultPort = port <= 0
            || ("http".equalsIgnoreCase(scheme) && port == 80)
            || ("https".equalsIgnoreCase(scheme) && port == 443);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
    }

    /**
     * Sets {@link #OWN_ORIGIN_HEADER} from the request's scheme, server name
     * and port, which honour the proxy's forwarded headers. Whatever a client
     * sent under that name is replaced, never trusted.
     */
    static final class OwnOrigin extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String own = originOf(request.getScheme(), request.getServerName(), request.getServerPort());
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override
                public String getHeader(String name) {
                    return OWN_ORIGIN_HEADER.equalsIgnoreCase(name) ? own : super.getHeader(name);
                }

                @Override
                public Enumeration<String> getHeaders(String name) {
                    return OWN_ORIGIN_HEADER.equalsIgnoreCase(name)
                        ? Collections.enumeration(List.of(own)) : super.getHeaders(name);
                }

                @Override
                public Enumeration<String> getHeaderNames() {
                    Set<String> names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
                    names.removeIf(OWN_ORIGIN_HEADER::equalsIgnoreCase);
                    names.add(OWN_ORIGIN_HEADER);
                    return Collections.enumeration(names);
                }
            }, response);
        }
    }
}
