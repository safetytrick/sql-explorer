package com.github.safetytrick.sqlexplorer.evidence;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which of an environment's sources a question is about, by source ref — for
 * a live source, the server's name (sqlx-a9h.1).
 *
 * <p>A scope narrows the <em>data</em>, not the matches: the corpus is merged
 * from the chosen sources' repositories alone, so a statement that ran on the
 * primary and a replica, scoped to the replica, carries the replica's
 * executions and time only. Filtering the merged corpus by "ran there" would
 * instead keep both servers' totals, which answers no question about where
 * load lands.
 *
 * <p>{@code include} empty means every source; {@code exclude} then removes
 * some. Refs compare case-insensitively, as server names are written by hand
 * in config and in links.
 */
public record SourceScope(Set<String> include, Set<String> exclude) {

    public static final SourceScope EVERYTHING = new SourceScope(Set.of(), Set.of());

    public SourceScope {
        include = normalize(include);
        exclude = normalize(exclude);
    }

    public static SourceScope of(List<String> include, List<String> exclude) {
        return new SourceScope(include == null ? Set.of() : Set.copyOf(include),
            exclude == null ? Set.of() : Set.copyOf(exclude));
    }

    public static SourceScope only(String sourceRef) {
        return new SourceScope(Set.of(sourceRef), Set.of());
    }

    public boolean isEverything() {
        return include.isEmpty() && exclude.isEmpty();
    }

    public boolean includes(String sourceRef) {
        String ref = sourceRef == null ? "" : sourceRef.toLowerCase(Locale.ROOT);
        return (include.isEmpty() || include.contains(ref)) && !exclude.contains(ref);
    }

    private static Set<String> normalize(Set<String> refs) {
        if (refs == null) {
            return Set.of();
        }
        return refs.stream()
            .filter(ref -> ref != null && !ref.isBlank())
            .map(ref -> ref.trim().toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    }
}
