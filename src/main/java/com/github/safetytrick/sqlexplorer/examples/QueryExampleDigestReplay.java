package com.github.safetytrick.sqlexplorer.examples;

import java.util.List;

import com.github.safetytrick.sqlexplorer.analysis.parser.NormalizedQueryDigest;
import com.github.safetytrick.sqlexplorer.analysis.parser.QueryParameterizer;
import jakarta.annotation.PostConstruct;
import net.sf.jsqlparser.JSQLParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-derives stored example digests when the normalization scheme changes.
 *
 * <p>Query examples are the first thing in this application to key on a digest
 * <em>durably</em>. Everything else recomputes: the corpus is rebuilt on every
 * source load, so it is single-version by construction, which is why the
 * scheme could be changed twice without a versioning story. A row in
 * {@code query_example} does not recompute, so the next change to
 * {@link com.github.safetytrick.sqlexplorer.analysis.parser.QueryNormalizer}
 * would leave every stored example pointing at a group that no longer exists.
 *
 * <p>The repair is cheap only because the example stores the <em>statement</em>
 * alongside the digest. That makes this a replay — parameterize, renormalize,
 * rehash — rather than a search for which profile the old digest used to mean,
 * which is the hard case {@code normalization-versioning.md} describes for
 * digest-keyed tag paths. If anyone ever "optimizes" the text away, this class
 * stops being possible.
 *
 * <p>The stored session schema is reused rather than re-inferred, deliberately.
 * Inference reads the currently-loaded schema of the active environment, which
 * changes; re-inferring here would re-key examples against whatever the schema
 * happens to look like now, which is the failure the derive-don't-accept rule
 * exists to prevent, arriving by the back door.
 */
@Component
class QueryExampleDigestReplay {

    private static final Logger log = LoggerFactory.getLogger(QueryExampleDigestReplay.class);

    private final QueryExampleRepository repository;
    private final NormalizedQueryDigest normalizedQueryDigest;
    private final QueryParameterizer queryParameterizer;

    QueryExampleDigestReplay(QueryExampleRepository repository,
                             NormalizedQueryDigest normalizedQueryDigest,
                             QueryParameterizer queryParameterizer) {
        this.repository = repository;
        this.normalizedQueryDigest = normalizedQueryDigest;
        this.queryParameterizer = queryParameterizer;
    }

    /**
     * On startup, because that is when a new build — and therefore possibly a
     * new scheme — first meets the stored rows.
     */
    @PostConstruct
    void replayOnStartup() {
        try {
            int updated = replayStaleDigests();
            if (updated > 0) {
                log.info("Re-derived {} query-example digest(s) for normalizer {}",
                    updated, NormalizedQueryDigest.versionPrefix());
            }
        } catch (RuntimeException e) {
            // A replay failure must not stop the application: the examples are
            // still readable by id, and a stale digest is a wrong association
            // rather than a broken one.
            log.warn("Could not re-derive query-example digests", e);
        }
    }

    /**
     * @return how many rows were re-keyed
     */
    @Transactional
    int replayStaleDigests() {
        List<QueryExample> stale = repository.findAll().stream()
            .filter(example -> !NormalizedQueryDigest.isCurrentVersion(example.getNormalizedDigest()))
            .toList();

        int updated = 0;
        for (QueryExample example : stale) {
            String digest = rederive(example);
            if (digest == null || digest.equals(example.getNormalizedDigest())) {
                continue;
            }
            example.setNormalizedDigest(digest);
            repository.save(example);
            updated++;
        }
        return updated;
    }

    /**
     * The same pipeline the write path uses — parameterize, then hash within the
     * stored schema — so a replayed digest is identical to what submitting the
     * example again today would produce.
     *
     * @return the digest, or null when the statement can no longer be parsed
     */
    private String rederive(QueryExample example) {
        try {
            String parameterized = queryParameterizer.parameterize(example.getQueryText());
            return normalizedQueryDigest.normalizedDigest(parameterized, example.getSessionSchema());
        } catch (JSQLParserException e) {
            // It parsed when it was stored, so this means the parser changed
            // under it. Leaving the old digest is the lesser wrong: it points
            // somewhere stale rather than nowhere.
            log.warn("Could not re-derive the digest for example {}: {}", example.getId(), e.getMessage());
            return null;
        }
    }
}
