package com.github.safetytrick.sqlexplorer.environment;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;

@Component
@RequestScope
public class EnvironmentContext {

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
