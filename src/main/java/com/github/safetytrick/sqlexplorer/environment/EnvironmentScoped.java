package com.github.safetytrick.sqlexplorer.environment;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller whose every endpoint answers about one environment. Its
 * mappings are served under {@value #PREFIX} (see {@code WebConfig}), so the
 * environment a request is about is part of its URL rather than a header
 * (sqlx-e13.6). That is what lets authorization be decided from the path
 * alone (sqlx-e13.4), and it means an unknown environment is a 404 instead of
 * a silent answer from the default one.
 *
 * <p>A controller without it is global: {@code /environments}, {@code /user},
 * pure utilities. Putting an environment-scoped endpoint on such a controller
 * fails at the first request, because {@link EnvironmentContext} finds no
 * environment in the path.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EnvironmentScoped {

    /** The path variable holding the environment's name. */
    String VARIABLE = "env";

    /** Prepended to every mapping of an annotated controller. */
    String PREFIX = "/env/{" + VARIABLE + "}";
}
