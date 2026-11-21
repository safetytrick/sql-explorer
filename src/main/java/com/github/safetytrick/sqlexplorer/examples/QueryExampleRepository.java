package com.github.safetytrick.sqlexplorer.examples;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface QueryExampleRepository extends JpaRepository<QueryExample, UUID> {

    List<QueryExample> findByNormalizedDigestOrderByIdDesc(String normalizedDigest, Pageable pageable);

    long countByNormalizedDigest(String normalizedDigest);

    @org.springframework.data.jpa.repository.Query(
        "select distinct e.normalizedDigest from QueryExample e")
    java.util.Set<String> findDistinctNormalizedDigests();
}
