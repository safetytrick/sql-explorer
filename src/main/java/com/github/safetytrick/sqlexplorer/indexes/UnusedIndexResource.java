package com.github.safetytrick.sqlexplorer.indexes;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Indexes no connection in the environment has read.
 *
 * <p>Read the report's own {@code basis} before acting on it: these are
 * Performance Schema counters, and what they can support depends entirely on
 * how long each connection has been counting (sqlx-vjr).
 */
@Tag(name = "Indexes")
@RestController
class UnusedIndexResource {

    private final UnusedIndexService service;
    private final EnvironmentContext environmentContext;

    UnusedIndexResource(UnusedIndexService service, EnvironmentContext environmentContext) {
        this.service = service;
        this.environmentContext = environmentContext;
    }

    @GetMapping("/unused-indexes")
    public UnusedIndexes.Report current() {
        return service.report(environmentContext.current().getName());
    }
}
