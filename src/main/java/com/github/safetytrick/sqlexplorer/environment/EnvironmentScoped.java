package com.github.safetytrick.sqlexplorer.environment;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EnvironmentScoped {

    String VARIABLE = "env";

    String PREFIX = "/env/{" + VARIABLE + "}";
}
