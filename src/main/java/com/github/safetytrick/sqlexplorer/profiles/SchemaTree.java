package com.github.safetytrick.sqlexplorer.profiles;

import java.util.List;

/**
 * Sidebar payload for {@code GET /schema-tree}: every known schema and table
 * with column count (null when the table is only seen in queries, not in the
 * loaded schema), distinct query count, and whether any of its queries has an
 * analyzer finding.
 */
public record SchemaTree(List<SchemaNode> schemas) {

    public record SchemaNode(String name, List<TableNode> tables) { }

    public record TableNode(String name, Integer columnCount, int queryCount, boolean warn) { }
}
