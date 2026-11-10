package com.github.safetytrick.sqlexplorer.environment;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Resolves the environment selected for the current request from the
 * {@value #HEADER} header, falling back to the default environment when the
 * header is absent or names an unknown environment. Request-scoped so it is
 * only used on the REST query path (never on background import threads, which
 * resolve the environment explicitly).
 */
@Component
@RequestScope
public class EnvironmentContext {

    public static final String HEADER = "X-Sql-Explorer-Environment";

    /**
     * A request attribute naming the environment, which wins over the header:
     * how an MCP tool call that names its own environment selects it, as one
     * HTTP request to {@code /mcp} carries one tool call.
     */
    public static final String OVERRIDE_ATTRIBUTE = EnvironmentContext.class.getName() + ".override";

    private final EnvironmentRegistry registry;
    private final HttpServletRequest request;

    public EnvironmentContext(EnvironmentRegistry registry, HttpServletRequest request) {
        this.registry = registry;
        this.request = request;
    }

    public Environment current() {
        Object override = request.getAttribute(OVERRIDE_ATTRIBUTE);
        return registry.getOrDefault(override != null ? override.toString() : request.getHeader(HEADER));
    }
}
