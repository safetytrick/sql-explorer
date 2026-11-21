package com.github.safetytrick.sqlexplorer.examples;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class QueryExampleLookup {

    public record Example(UUID id, String queryText, String sessionSchema) { }

    private final QueryExampleRepository repository;

    QueryExampleLookup(QueryExampleRepository repository) {
        this.repository = repository;
    }

    public Example byId(UUID id) {
        return repository.findById(id).map(QueryExampleLookup::toExample).orElse(null);
    }

    public Example latestForDigest(String normalizedDigest) {
        List<QueryExample> found =
            repository.findByNormalizedDigestOrderByIdDesc(normalizedDigest, PageRequest.of(0, 1));
        return found.isEmpty() ? null : toExample(found.getFirst());
    }

    public java.util.Set<String> digestsWithExamples() {
        return repository.findDistinctNormalizedDigests();
    }

    private static Example toExample(QueryExample example) {
        return new Example(example.getId(), example.getQueryText(), example.getSessionSchema());
    }
}
