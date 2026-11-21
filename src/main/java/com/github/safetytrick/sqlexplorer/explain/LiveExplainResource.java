package com.github.safetytrick.sqlexplorer.explain;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentScoped;
import java.util.UUID;

import com.github.safetytrick.sqlexplorer.examples.QueryExampleLookup;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Explain")
@EnvironmentScoped
@RestController
class LiveExplainResource {

    private final LiveExplainService liveExplainService;
    private final QueryExampleLookup exampleLookup;

    LiveExplainResource(LiveExplainService liveExplainService, QueryExampleLookup exampleLookup) {
        this.liveExplainService = liveExplainService;
        this.exampleLookup = exampleLookup;
    }

    record ExplainResult(String plan, String serverName, UUID exampleId) { }

    record ExplainRefused(String reason, String detail) { }

    @PostMapping("/query-examples/{exampleId}/explain")
    public ResponseEntity<?> explainExample(@PathVariable UUID exampleId) {
        QueryExampleLookup.Example example = exampleLookup.byId(exampleId);
        if (example == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ExplainRefused("NO_SUCH_EXAMPLE", "no example with id " + exampleId));
        }
        return respond(liveExplainService.explain(example.queryText(), example.sessionSchema()), exampleId);
    }

    @PostMapping("/analyzed-sql/{digest}/explain")
    public ResponseEntity<?> explainLatestExampleOf(@PathVariable String digest) {
        QueryExampleLookup.Example example = exampleLookup.latestForDigest(digest);
        if (example == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ExplainRefused(
                "NO_EXAMPLE",
                "no query example is stored for this digest. The corpus holds MySQL's digest text, "
                    + "whose values are ? placeholders and cannot be planned — add an example with "
                    + "real parameters via POST /query-examples first."));
        }
        return respond(liveExplainService.explain(example.queryText(), example.sessionSchema()), example.id());
    }

    private ResponseEntity<?> respond(LiveExplainService.Result result, UUID exampleId) {
        if (result.succeeded()) {
            return ResponseEntity.ok(new ExplainResult(result.plan(), result.serverName(), exampleId));
        }
        HttpStatus status = switch (result.failure()) {
            case NOT_ENABLED -> HttpStatus.FORBIDDEN;
            case NO_SERVER -> HttpStatus.SERVICE_UNAVAILABLE;
            case OFFLINE -> HttpStatus.CONFLICT;
            case REFUSED -> HttpStatus.BAD_REQUEST;
            case EXECUTION_FAILED -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status)
            .body(new ExplainRefused(result.failure().name(), result.detail()));
    }
}
