package com.github.safetytrick.sqlexplorer.indexes;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    /**
     * The report last read for the current environment, or an empty body when
     * none has been since startup. Never connects to anything (sqlx-1bo.2): an
     * empty 200 rather than a 404, so the page can tell "not read yet" apart
     * from the backend being down.
     */
    @GetMapping("/unused-indexes")
    public UnusedIndexes.Report latest() {
        return service.latest(environmentContext.current().getName()).orElse(null);
    }

    /** Read every connection of the current environment now; the answer becomes the latest. */
    @PostMapping("/unused-indexes/read")
    public UnusedIndexes.Report read() {
        return service.report(environmentContext.current().getName());
    }
}
