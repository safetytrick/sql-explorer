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

    @PostConstruct
    void replayOnStartup() {
        try {
            int updated = replayStaleDigests();
            if (updated > 0) {
                log.info("Re-derived {} query-example digest(s) for normalizer {}",
                    updated, NormalizedQueryDigest.versionPrefix());
            }
        } catch (RuntimeException e) {
            log.warn("Could not re-derive query-example digests", e);
        }
    }

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

    private String rederive(QueryExample example) {
        try {
            String parameterized = queryParameterizer.parameterize(example.getQueryText());
            return normalizedQueryDigest.normalizedDigest(parameterized, example.getSessionSchema());
        } catch (JSQLParserException e) {
            log.warn("Could not re-derive the digest for example {}: {}", example.getId(), e.getMessage());
            return null;
        }
    }
}
