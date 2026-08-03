package com.github.safetytrick.sqlexplorer.profiles;

import java.util.List;

/**
 * Sidebar payload for {@code GET /schema-tree}: every known schema and table
 * with column count (null when the table is only seen in queries, not in the
 * loaded schema), distinct query count, whether any of its queries has an
 * analyzer finding, and whether the "table" is actually a view (per the
 * loaded schema metadata; false when the table is only seen in queries).
 */
public record SchemaTree(List<SchemaNode> schemas) {

    public record SchemaNode(String name, List<TableNode> tables) { }

    public record TableNode(String name, Integer columnCount, int queryCount, boolean warn, boolean view) { }
}
