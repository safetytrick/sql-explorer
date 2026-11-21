package com.github.safetytrick.sqlexplorer.evidence;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

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
