package com.github.safetytrick.sqlexplorer.profiles;

import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.commentary.Tag;

/**
 * Result page for {@code POST /queries/search}: {@code total} counts all matches
 * before paging. {@code facets} carries per-table / per-schema match counts over
 * the set matching every filter except the table/schema scope selection, so the
 * schema tree can annotate each node with "picking this yields N".
 */
public record QuerySearchResult(int total, List<QueryHit> results, Facets facets) {

    public record QueryHit(SqlProfile profile, List<Tag> tags) { }

    /** Match counts keyed by normalized {@code schema.table} and normalized schema name. */
    public record Facets(Map<String, Integer> tables, Map<String, Integer> schemas) { }
}
