package com.github.safetytrick.sqlexplorer.environment;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;

/**
 * The environment the current request is about: the {@code {env}} segment of
 * an {@link EnvironmentScoped} controller's path ({@code /env/prod/schemas}).
 *
 * <p>NO FALLBACK. This used to read a header and answer with the default
 * environment when the header was missing or named an environment that does
 * not exist, so a typo or a stale link got another environment's data with no
 * sign of it. Now the environment is in the URL, where authorization can read
 * it (sqlx-e13.6), and an unknown name is a 404.
 *
 * <p>Request-scoped, so it is only used on the request path; background
 * imports resolve their environment explicitly.
 */
@Component
@RequestScope
public class EnvironmentContext {

    /**
     * A request attribute naming the environment, which wins over the path:
     * how an MCP tool call that names its own environment selects it, as one
     * HTTP request to {@code /mcp} carries one tool call. Validated by the
     * caller before it is set.
     */
    public static final String OVERRIDE_ATTRIBUTE = EnvironmentContext.class.getName() + ".override";

    private final EnvironmentRegistry registry;
    private final HttpServletRequest request;

    public EnvironmentContext(EnvironmentRegistry registry, HttpServletRequest request) {
        this.registry = registry;
        this.request = request;
    }

    public Environment current() {
        String name = named();
        Environment environment = registry.get(name);
        if (environment == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no environment \"" + name + "\"");
        }
        return environment;
    }

    private String named() {
        if (request.getAttribute(OVERRIDE_ATTRIBUTE) instanceof String override) {
            return override;
        }
        if (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> vars
                && vars.get(EnvironmentScoped.VARIABLE) instanceof String name) {
            return name;
        }
        throw new IllegalStateException(request.getRequestURI() + " names no environment: an endpoint that"
            + " depends on one belongs on an @EnvironmentScoped controller");
    }
}
