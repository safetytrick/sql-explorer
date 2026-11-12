package com.github.safetytrick.sqlexplorer.examples;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentScoped;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Example queries — one real statement, parameters intact, for a normalized
 * query group.
 *
 * <p>Note what the write endpoint is <em>not</em>:
 * {@code POST /analyzed-sql/{digest}/examples}. Nothing would check that the
 * submitted text normalizes to the digest in the path, so any statement could
 * be attached to any group and every reader downstream would believe it. The
 * digest is derived from the statement here and the association is a computed
 * fact rather than a caller's claim.
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Query examples")
@EnvironmentScoped
@RestController
class QueryExampleResource {

    private final QueryExampleService service;

    QueryExampleResource(QueryExampleService service) {
        this.service = service;
    }

    /**
     * @param sessionSchema optional. Omit it and the schema is inferred from the
     *                      statement's own tables — the same machinery that
     *                      scoped the profile this example will attach to. State
     *                      it and it is believed.
     */
    record CreateExample(@NotBlank String queryText, String sessionSchema, String description) { }

    /**
     * @param matchedAProfile whether a loaded profile carries the derived
     *                        digest. False is not an error — the corpus is a
     *                        live window and a statement can age out of it —
     *                        but it is also what a wrong parameterization looks
     *                        like, so it is reported rather than left silent.
     *                        Null on a read, where it has not been evaluated.
     */
    record ExampleView(UUID id, String queryText, String normalizedDigest, String sessionSchema,
                       boolean schemaInferred, String description, String environmentName,
                       Instant createdOn, Boolean matchedAProfile) {

        static ExampleView of(QueryExample example) {
            return of(example, null);
        }

        static ExampleView of(QueryExample example, Boolean matchedAProfile) {
            return new ExampleView(example.getId(), example.getQueryText(), example.getNormalizedDigest(),
                example.getSessionSchema(), example.isSchemaInferred(), example.getDescription(),
                example.getEnvironmentName(), example.getCreatedOn(), matchedAProfile);
        }
    }

    /**
     * Why the submission was rejected, and — when the schema could not be
     * inferred to a single answer — which schemas it could have meant, so the
     * caller can choose one and resubmit rather than guess.
     */
    record ExampleRejected(String reason, String detail, List<String> candidateSchemas) { }

    @PostMapping("/query-examples")
    public ResponseEntity<?> create(@RequestBody CreateExample request) {
        if (request == null || request.queryText() == null || request.queryText().isBlank()) {
            return ResponseEntity.badRequest()
                .body(new ExampleRejected("EMPTY_QUERY", "queryText is required", List.of()));
        }

        QueryExampleService.Result result =
            service.create(request.queryText(), request.sessionSchema(), request.description());

        if (result.succeeded()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(ExampleView.of(result.stored(), result.matchedAProfile()));
        }

        // 409 for the ambiguous case rather than 400: the request is well-formed
        // and the caller did nothing wrong — the statement genuinely does not
        // determine one schema, and the candidate list is what resolves it.
        HttpStatus status = result.failure() == QueryExampleService.Failure.AMBIGUOUS_SCHEMA
            ? HttpStatus.CONFLICT
            : HttpStatus.BAD_REQUEST;

        return ResponseEntity.status(status).body(
            new ExampleRejected(result.failure().name(), result.detail(), result.candidateSchemas()));
    }

    /**
     * Examples for a digest, most-recently-added first.
     *
     * <p>An example whose digest matches no currently-loaded profile is still
     * returned. The corpus is a live window over {@code sys.x$statement_analysis}
     * and a statement leaves it the moment it stops being among the top
     * consumers; tying reads to import state would hide exactly the rare
     * expensive statement worth keeping an example of.
     */
    @GetMapping("/query-examples/{digest}")
    public List<ExampleView> forDigest(@PathVariable String digest,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        int limit = Math.clamp(size, 1, 200);
        return service.forDigest(digest, PageRequest.of(Math.max(page, 0), limit))
            .stream().map(ExampleView::of).toList();
    }

    @GetMapping("/query-examples/{digest}/count")
    public long countForDigest(@PathVariable String digest) {
        return service.countForDigest(digest);
    }
}
