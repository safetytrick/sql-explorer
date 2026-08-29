package com.github.safetytrick.sqlexplorer.examples;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Read-only access to stored examples for code outside this package.
 *
 * <p>Exists so the explain feature can fetch a statement without reaching for
 * the entity or the repository, both of which are package-private. It hands
 * back the query text and nothing else it does not need — the caller is about
 * to send that text to a database, and the narrower the seam, the easier it is
 * to see that no request-supplied string can travel through it.
 */
@Service
public class QueryExampleLookup {

    /** The parts of a stored example anything outside this package should see. */
    public record Example(UUID id, String queryText, String sessionSchema) { }

    private final QueryExampleRepository repository;

    QueryExampleLookup(QueryExampleRepository repository) {
        this.repository = repository;
    }

    /** @return the example, or null if there is no such example */
    public Example byId(UUID id) {
        return repository.findById(id).map(QueryExampleLookup::toExample).orElse(null);
    }

    /**
     * The most recently added example for a digest, or null if there is none.
     * Most-recent is by id, which is a UUIDv7 and therefore chronological.
     */
    public Example latestForDigest(String normalizedDigest) {
        List<QueryExample> found =
            repository.findByNormalizedDigestOrderByIdDesc(normalizedDigest, PageRequest.of(0, 1));
        return found.isEmpty() ? null : toExample(found.getFirst());
    }

    /**
     * The digests that have at least one example, for the workbench's
     * "has examples" filter. Fetched whole because the caller is filtering a
     * corpus, not answering one question.
     */
    public java.util.Set<String> digestsWithExamples() {
        return repository.findDistinctNormalizedDigests();
    }

    private static Example toExample(QueryExample example) {
        return new Example(example.getId(), example.getQueryText(), example.getSessionSchema());
    }
}
