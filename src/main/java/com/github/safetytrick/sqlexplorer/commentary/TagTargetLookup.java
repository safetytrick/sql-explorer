package com.github.safetytrick.sqlexplorer.commentary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * The tags attached to a batch of targets, answered in the order asked.
 *
 * <p>WHY BY TARGET AND NOT BY PATH. The list pages ask about a few hundred rows
 * at once, and used to send path strings and key the answer by them. A table
 * whose name contains {@code /} has no path form at all (see {@link TagTarget}),
 * so a path-keyed lookup could neither ask about one nor return one — the
 * storage became able to hold such a tag in sqlx-11v while the API still could
 * not address it. This is the other half (sqlx-3ai).
 *
 * <p>THE ANSWER IS POSITIONAL, and that contract is load-bearing: the caller
 * zips it against the rows it sent. Nothing in the type system enforces it, so
 * it is pinned by tests — a reordering here would attribute every tag to the
 * wrong row, and would look like nothing at all.
 *
 * <p>Grouped into one query per kind rather than one per target. Table targets
 * are fetched by schema and matched to their exact table in memory, because a
 * page's rows sit in a handful of schemas and a tuple IN is not portable.
 */
@Service
public class TagTargetLookup {

    private final TagRepository tagRepository;
    private final CommentaryEnvironment environment;

    TagTargetLookup(TagRepository tagRepository, CommentaryEnvironment environment) {
        this.tagRepository = tagRepository;
        this.environment = environment;
    }

    /**
     * @param targets what to ask about, in the caller's order
     * @return one list per target, in that same order; empty where nothing is
     *         attached, never null and never omitted
     */
    public List<List<Tag>> tagsFor(List<TagTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        List<TagTarget> asked = targets.stream().map(TagTarget::normalized).toList();
        String environmentName = environment.name();

        Map<TagTarget, List<Tag>> found = new HashMap<>();
        collect(found, asked, TagTarget.SCHEMA, environmentName);
        collect(found, asked, TagTarget.TABLE, environmentName);
        collect(found, asked, TagTarget.COLUMN, environmentName);
        collect(found, asked, TagTarget.QUERY, environmentName);

        List<List<Tag>> answer = new ArrayList<>(asked.size());
        for (TagTarget target : asked) {
            // A duplicate target is legitimate — a caller may list the same
            // table twice — and gets the same answer at both positions.
            answer.add(found.getOrDefault(target, List.of()));
        }
        return List.copyOf(answer);
    }

    private void collect(Map<TagTarget, List<Tag>> into, List<TagTarget> asked,
                         String type, String environmentName) {
        List<TagTarget> ofType = asked.stream().filter(target -> type.equals(target.type())).toList();
        if (ofType.isEmpty()) {
            return;
        }
        List<Tag> rows = TagTarget.QUERY.equals(type)
            ? tagRepository.findAllByTypeAndQueryDigestInAndEnvironmentName(
                type, ofType.stream().map(TagTarget::digest).distinct().toList(), environmentName)
            : tagRepository.findAllByTypeAndSchemaNameInAndEnvironmentName(
                type, ofType.stream().map(TagTarget::schema).distinct().toList(), environmentName);

        for (Tag tag : rows) {
            // Matched on the exact target, which is what makes the schema-wide
            // over-fetch above safe: another table's tags simply do not match.
            into.computeIfAbsent(tag.getTarget(), key -> new ArrayList<>()).add(tag);
        }
    }
}
