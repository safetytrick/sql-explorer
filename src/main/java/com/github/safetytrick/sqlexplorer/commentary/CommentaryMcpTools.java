package com.github.safetytrick.sqlexplorer.commentary;

import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.mcp.McpArgs;
import com.github.safetytrick.sqlexplorer.mcp.McpTool;
import com.github.safetytrick.sqlexplorer.mcp.McpToolProvider;
import org.springframework.stereotype.Component;

/**
 * Tags as MCP tools: reading them, and the only writes the endpoint has.
 *
 * <p>Tagging is how a person curates what the queries mean, so a model that
 * can read every query but not label what it found would leave them retyping
 * its conclusions. The writes are small and reversible: a tag is an
 * annotation, re-adding one is a no-op, and removing one deletes that one
 * tagging rather than the tag everywhere. Notes and source loading are not
 * exposed. Targets go to the API typed ({@link TagTarget}), so a name with a
 * {@code /} in it is addressed correctly.
 */
@Component
class CommentaryMcpTools implements McpToolProvider {

    private static final String TARGET_DIGEST = "tag a query — its `digest` field from search_queries / get_query. Use "
        + "that field, not `id`: the UI hangs query tags off the digest, and a tag on anything else stays invisible there";

    private final TagManagerResource tagManager;
    private final SqlTagsResource sqlTags;
    private final TagsResource tags;

    CommentaryMcpTools(TagManagerResource tagManager, SqlTagsResource sqlTags, TagsResource tags) {
        this.tagManager = tagManager;
        this.sqlTags = sqlTags;
        this.tags = tags;
    }

    @Override
    public List<McpTool> tools() {
        return List.of(
            McpTool.named("list_tags", "All tag definitions with colors and usage counts (queries / tables / schemas).")
                .handler(args -> tagManager.listTags()),
            McpTool.named("get_queries_by_tag", "The analyzed queries carrying a tag.")
                .string("tag", null)
                .handler(args -> McpArgs.listOf(sqlTags.getSqlByTag(args.string("tag")))),
            targeted(McpTool.named("add_tag",
                    "Tag a query, table, or schema. Re-tagging something returns the existing tag rather than duplicating it.")
                .string("tag", "tag name: letters, numbers, underscores and hyphens", "^[\\w-]+$"))
                .writes()
                .handler(args -> {
                    TagsResource.CreateTag create = new TagsResource.CreateTag();
                    create.setName(args.string("tag"));
                    create.setTarget(targetOf(args));
                    return tags.createTag(create);
                }),
            targeted(McpTool.named("remove_tag", "Remove a tag from a query, table, or schema. Only that one tagging "
                    + "is deleted — the tag stays on everything else carrying it.")
                .string("tag", "tag name, matched case-insensitively"))
                .writes()
                .handler(args -> {
                    TagTarget target = targetOf(args);
                    String name = args.string("tag");
                    List<Tag> present = tags.tagsForTargets(List.of(target)).getFirst();
                    Tag match = present.stream().filter(tag -> tag.getName().equalsIgnoreCase(name)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("no tag \"" + name + "\" on " + target.path()
                            + " — " + (present.isEmpty() ? "it has no tags"
                                : "tags there: " + String.join(", ", present.stream().map(Tag::getName).toList()))));
                    tags.deleteTag(match.getId());
                    return Map.of("removed", match);
                })
        );
    }

    private static McpTool.Builder targeted(McpTool.Builder builder) {
        return builder
            .optionalString("queryDigest", TARGET_DIGEST)
            .optionalString("schema", "tag this schema, or with `table`, a table in it")
            .optionalString("table", "tag this table inside `schema`");
    }

    /** A query by digest, or a schema, or a table in one: exactly one of those. */
    static TagTarget targetOf(McpArgs args) {
        String digest = args.optionalString("queryDigest");
        String schema = args.optionalString("schema");
        String table = args.optionalString("table");
        if (digest != null && !digest.isBlank()) {
            if (schema != null || table != null) {
                throw new IllegalArgumentException("tag either a query or a schema/table, not both");
            }
            return TagTarget.ofQuery(digest).validated();
        }
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException("name a target: queryDigest, schema, or schema + table");
        }
        return (table == null || table.isBlank() ? TagTarget.ofSchema(schema) : TagTarget.ofTable(schema, table)).validated();
    }
}
