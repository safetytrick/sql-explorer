package com.github.safetytrick.sqlexplorer.profiles;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.commentary.Tag;

/** Result page for {@code POST /queries/search}: {@code total} counts all matches before paging. */
public record QuerySearchResult(int total, List<QueryHit> results) {

    public record QueryHit(SqlProfile profile, List<Tag> tags) { }
}
