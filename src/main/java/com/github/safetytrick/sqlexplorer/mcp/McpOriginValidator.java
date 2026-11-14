package com.github.safetytrick.sqlexplorer.mcp;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;

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

    private final ServerTransportSecurityValidator local =
        DefaultServerTransportSecurityValidator.builder().allowedOrigins(LOCAL_ORIGINS).build();
    private final boolean signInOn;

    McpOriginValidator(boolean signInOn) {
        this.signInOn = signInOn;
    }

    @Override
    public void validateHeaders(Map<String, List<String>> headers) throws ServerTransportSecurityException {
        String origin = first(headers, "Origin");
        if (signInOn && origin != null && origin.equalsIgnoreCase(ownOrigin(headers))) {
            return;
        }
        local.validateHeaders(headers);
    }

    /**
     * The address the request was made to, as {@code scheme://host[:port]} with
     * a default port left out, which is how a browser writes an Origin. Read
     * from the proxy's X-Forwarded-* headers (the UI's nginx always sends
     * them), else from Host.
     */
    static String ownOrigin(Map<String, List<String>> headers) {
        String proto = firstValue(first(headers, "X-Forwarded-Proto"));
        String host = firstValue(first(headers, "X-Forwarded-Host"));
        if (host == null) {
            host = first(headers, "Host");
        }
        if (host == null) {
            return null;
        }
        String scheme = proto == null ? "http" : proto.toLowerCase(Locale.ROOT);
        String port = firstValue(first(headers, "X-Forwarded-Port"));
        int colon = host.lastIndexOf(':');
        if (colon > host.lastIndexOf(']')) {
            // the host names its own port
            port = host.substring(colon + 1);
            host = host.substring(0, colon);
        }
        boolean defaultPort = port == null
            || ("http".equals(scheme) && "80".equals(port))
            || ("https".equals(scheme) && "443".equals(port));
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
    }

    /** A header's first value, its name matched in any case. */
    private static String first(Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream()
            .filter(entry -> entry.getKey() != null && entry.getKey().equalsIgnoreCase(name))
            .flatMap(entry -> entry.getValue().stream())
            .findFirst().orElse(null);
    }

    /** The first of a comma-separated list, as a chain of proxies writes X-Forwarded-*. */
    private static String firstValue(String value) {
        if (value == null) {
            return null;
        }
        String first = value.split(",", 2)[0].trim();
        return first.isEmpty() ? null : first;
    }
}
