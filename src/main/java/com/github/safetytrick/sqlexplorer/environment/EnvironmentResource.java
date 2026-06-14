package com.github.safetytrick.sqlexplorer.environment;

import java.util.List;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

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

    record EnvironmentList(String defaultEnvironment, List<EnvironmentSummary> environments) { }

    record EnvironmentSummary(String name, List<String> servers) { }
}
