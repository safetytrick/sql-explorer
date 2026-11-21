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

    @GetMapping("/coverage")
    public Coverage.Report current() {
        return coverage.report(environmentContext.current().getName());
    }

    @GetMapping("/coverage/{name}")
    public Coverage.Report named(@PathVariable String name) {
        Environment environment = registry.get(name);
        if (environment == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown environment: " + name);
        }
        return coverage.report(environment.getName());
    }
}
