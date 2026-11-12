package com.github.safetytrick.sqlexplorer.coverage;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentScoped;
import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * How much of an environment sql-explorer actually saw.
 *
 * <p>Read this before concluding anything from an absence. "No query touches
 * this table" is a claim about the capture as much as about the table, and
 * these findings are what separate the two (sqlx-n01).
 */
@Tag(name = "Coverage")
@EnvironmentScoped
@RestController
class CoverageResource {

    private final CoverageService coverage;
    private final EnvironmentContext environmentContext;
    private final EnvironmentRegistry registry;

    CoverageResource(CoverageService coverage, EnvironmentContext environmentContext, EnvironmentRegistry registry) {
        this.coverage = coverage;
        this.environmentContext = environmentContext;
        this.registry = registry;
    }

    /** Coverage for the environment selected on the request. */
    @GetMapping("/coverage")
    public Coverage.Report current() {
        return coverage.report(environmentContext.current().getName());
    }

    /**
     * Coverage for a named environment, for comparing two of them without
     * juggling the request header.
     */
    @GetMapping("/coverage/{name}")
    public Coverage.Report named(@PathVariable String name) {
        Environment environment = registry.get(name);
        if (environment == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown environment: " + name);
        }
        return coverage.report(environment.getName());
    }
}
