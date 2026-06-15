package com.github.safetytrick.sqlexplorer.environment;

import java.util.List;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Tag(name = "Environment")
@RestController
class EnvironmentResource {

    private final EnvironmentRegistry registry;

    EnvironmentResource(EnvironmentRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/environments")
    public EnvironmentList list() {
        List<EnvironmentSummary> environments = registry.list().stream()
            .map(environment -> new EnvironmentSummary(
                environment.getName(),
                environment.getServers().stream().map(DatabaseServer::getName).toList()))
            .toList();
        return new EnvironmentList(registry.getDefaultName(), environments);
    }

    /** Drop the environment's cached schema so it reloads from its servers on next use. */
    @PostMapping("/environments/{name}/refresh-schema")
    public ResponseEntity<Void> refreshSchema(@PathVariable String name) {
        Environment environment = registry.get(name);
        if (environment == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown environment: " + name);
        }
        environment.refreshSchema();
        return ResponseEntity.noContent().build();
    }

    record EnvironmentList(String defaultEnvironment, List<EnvironmentSummary> environments) { }

    record EnvironmentSummary(String name, List<String> servers) { }
}
