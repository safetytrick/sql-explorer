package com.github.safetytrick.sqlexplorer.examples;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface QueryExampleRepository extends JpaRepository<QueryExample, UUID> {

    /**
     * Every read is "the examples for this digest", which is what the index on
     * {@code normalized_digest} exists for.
     *
     * <p>Ordered by id descending, which is most-recently-added first because
     * the key is a UUIDv7 — the timestamp is in the high bits, so the primary
     * key ordering is chronological ordering and no separate sort column is
     * needed.
     */
    List<QueryExample> findByNormalizedDigestOrderByIdDesc(String normalizedDigest, Pageable pageable);

    long countByNormalizedDigest(String normalizedDigest);

    /**
     * Every digest that has at least one example.
     *
     * <p>The whole set in one query rather than a lookup per row: the queries
     * workbench filters thousands of profiles at a time, and asking per profile
     * would be that many round trips to answer one checkbox.
     */
    @org.springframework.data.jpa.repository.Query(
        "select distinct e.normalizedDigest from QueryExample e")
    java.util.Set<String> findDistinctNormalizedDigests();
}
