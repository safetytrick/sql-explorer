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

@io.swagger.v3.oas.annotations.tags.Tag(name = "Query examples")
@EnvironmentScoped
@RestController
class QueryExampleResource {

    private final QueryExampleService service;

    QueryExampleResource(QueryExampleService service) {
        this.service = service;
    }

    record CreateExample(@NotBlank String queryText, String sessionSchema, String description) { }

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

        HttpStatus status = result.failure() == QueryExampleService.Failure.AMBIGUOUS_SCHEMA
            ? HttpStatus.CONFLICT
            : HttpStatus.BAD_REQUEST;

        return ResponseEntity.status(status).body(
            new ExampleRejected(result.failure().name(), result.detail(), result.candidateSchemas()));
    }

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
