package com.github.safetytrick.sqlexplorer.commentary;

import java.util.List;

/**
 * The tags on one target.
 *
 * <p>KEYED BY TARGET, NOT PATH. A path is null for a name containing {@code /},
 * so grouping by one threw on the first such row — the tag existed, was correct,
 * and made every rollup for its schema fail (sqlx-3ai).
 */
public interface TagLoader {
    List<Tag> findAllByTarget(TagTarget target);
}
