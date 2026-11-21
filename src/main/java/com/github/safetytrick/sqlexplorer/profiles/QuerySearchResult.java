package com.github.safetytrick.sqlexplorer.profiles;

import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.commentary.Tag;

public record QuerySearchResult(int total, List<QueryHit> results, Facets facets) {

    public record QueryHit(SqlProfile profile, List<Tag> tags) { }

    public record Facets(Map<String, Integer> tables, Map<String, Integer> schemas, Map<String, Integer> sources) { }
}
