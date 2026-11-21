package com.github.safetytrick.sqlexplorer.indexes;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentScoped;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Indexes")
@EnvironmentScoped
@RestController
class UnusedIndexResource {

    private final UnusedIndexService service;
    private final EnvironmentContext environmentContext;

    UnusedIndexResource(UnusedIndexService service, EnvironmentContext environmentContext) {
        this.service = service;
        this.environmentContext = environmentContext;
    }

    @GetMapping("/unused-indexes")
    public UnusedIndexes.Report latest() {
        return service.latest(environmentContext.current().getName()).orElse(null);
    }

    @PostMapping("/unused-indexes/read")
    public UnusedIndexes.Report read() {
        return service.report(environmentContext.current().getName());
    }
}
