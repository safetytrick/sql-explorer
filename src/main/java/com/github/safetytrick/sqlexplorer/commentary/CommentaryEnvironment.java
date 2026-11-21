package com.github.safetytrick.sqlexplorer.commentary;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;

@Component
class CommentaryEnvironment {

    private final EnvironmentContext context;
    private final EnvironmentRegistry registry;

    CommentaryEnvironment(EnvironmentContext context, EnvironmentRegistry registry) {
        this.context = context;
        this.registry = registry;
    }

    String name() {
        if (RequestContextHolder.getRequestAttributes() == null) {
            return registry.getDefaultName();
        }
        return context.current().getName();
    }
}
