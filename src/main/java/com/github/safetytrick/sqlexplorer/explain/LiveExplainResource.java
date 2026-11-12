package com.github.safetytrick.sqlexplorer.explain;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentScoped;
import java.util.UUID;

import com.github.safetytrick.sqlexplorer.examples.QueryExampleLookup;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs {@code EXPLAIN} against the active environment's servers.
 *
 * <p><b>Every endpoint here takes an identifier and no SQL.</b> That is the
 * feature's central rule, and it is visible in the signatures: there is no
 * request body anywhere in this class, so there is no path by which a statement
 * from a caller could reach a database. The text is read from storage, and the
 * only thing prepended to it is {@code EXPLAIN FORMAT=JSON}.
 *
 * <p>The corpus itself cannot be explained — it holds MySQL's digest text,
 * whose values are {@code ?}, and that is a syntax error to {@code EXPLAIN}
 * (error 1064, verified on 8.4.4). So what gets planned is a stored
 * <em>example</em>: a real statement with its parameters intact. That is why
 * {@code sqlx-hlu} had to land first.
 */
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

    /**
     * @param plan       the server's {@code FORMAT=JSON} document, which the
     *                   frontend's existing explain parser already reads
     * @param serverName which server answered, since an environment may have
     *                   several and they need not plan alike
     * @param exampleId  the example that was planned — the response says what
     *                   ran, because "the plan for this query" is ambiguous
     *                   when a query has several examples
     */
    record ExplainResult(String plan, String serverName, UUID exampleId) { }

    record ExplainRefused(String reason, String detail) { }

    /**
     * Explain a stored example by its id.
     *
     * <p>Takes the example's id rather than a digest so the caller controls
     * <em>which</em> example is planned; a query with several examples has
     * several plans, and picking one silently would hide that.
     */
    @PostMapping("/query-examples/{exampleId}/explain")
    public ResponseEntity<?> explainExample(@PathVariable UUID exampleId) {
        QueryExampleLookup.Example example = exampleLookup.byId(exampleId);
        if (example == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ExplainRefused("NO_SUCH_EXAMPLE", "no example with id " + exampleId));
        }
        return respond(liveExplainService.explain(example.queryText(), example.sessionSchema()), exampleId);
    }

    /**
     * Explain the most recent example of a query.
     *
     * <p>The convenience form of the above, and it fails informatively rather
     * than falling back to the profile's own text: that text carries {@code ?}
     * placeholders and is not runnable, so "explain this query" without an
     * example has no answer worth giving.
     */
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
        // 403 when the environment has not opted in: the request is well formed
        // and the answer is a policy one, not a mistake the caller can correct
        // by rewording it.
        HttpStatus status = switch (result.failure()) {
            case NOT_ENABLED -> HttpStatus.FORBIDDEN;
            case NO_SERVER -> HttpStatus.SERVICE_UNAVAILABLE;
            // the environment is cached: a pull takes it live, so this is a state, not a fault
            case OFFLINE -> HttpStatus.CONFLICT;
            case REFUSED -> HttpStatus.BAD_REQUEST;
            case EXECUTION_FAILED -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status)
            .body(new ExplainRefused(result.failure().name(), result.detail()));
    }
}
