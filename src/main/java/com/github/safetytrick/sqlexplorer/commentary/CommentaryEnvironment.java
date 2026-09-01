package com.github.safetytrick.sqlexplorer.commentary;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Which environment a tag or note belongs to.
 *
 * <p>Commentary used to be keyed by path alone, so a tag applied while looking
 * at one environment attached to every environment with a schema of that name.
 * That was not theoretical: `regression` and `local` share `information_schema`,
 * `mysql`, `performance_schema` and `sys` — exactly the schemas the unqueried
 * page instructs you to tag (sqlx-rad).
 *
 * <p>ONE PLACE, deliberately. Every read and every write in this package needs
 * the same answer, and a single one of them forgetting to ask is a silent
 * cross-environment leak rather than an error. Resolving it here means the
 * repository methods can require it as a parameter, which turns "forgot to
 * scope this query" into a compile failure.
 */
@Component
class CommentaryEnvironment {

    private final EnvironmentContext context;
    private final EnvironmentRegistry registry;

    CommentaryEnvironment(EnvironmentContext context, EnvironmentRegistry registry) {
        this.context = context;
        this.registry = registry;
    }

    /**
     * The environment selected on the current request, or the default when
     * there is no request.
     *
     * <p>{@link EnvironmentContext} is request-scoped and reads a header, so it
     * cannot be resolved on a background thread. Checking for a request rather
     * than catching the failure keeps the fallback deliberate: work with no
     * request behind it belongs to the default environment, which is the same
     * thing an unset header means.
     */
    String name() {
        if (RequestContextHolder.getRequestAttributes() == null) {
            return registry.getDefaultName();
        }
        return context.current().getName();
    }
}
