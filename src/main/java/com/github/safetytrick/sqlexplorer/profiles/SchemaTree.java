package com.github.safetytrick.sqlexplorer.profiles;

import java.time.LocalDateTime;
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

    /**
     * @param inSchema whether the environment's schema metadata actually
     *                 describes this table. False means the node exists only
     *                 because a query named it — a view-swap artifact that has
     *                 since been dropped, a temp table, or a data-dictionary
     *                 table introspection cannot see. Distinct from a null
     *                 {@code columnCount}, which a real table also gets when
     *                 its column list did not load.
     * @param archivedLastSeen when a query last touched this table in a period
     *                 the loaded corpus no longer covers — set only for a table
     *                 with no queries in the current run, because that is the
     *                 only case where it changes the answer. Null is the
     *                 ordinary value and means "the archive knows nothing
     *                 extra", NOT "never used": before any reset there is no
     *                 earlier run to consult (sqlx-qwn).
     */
    public record TableNode(String name, Integer columnCount, int queryCount, boolean warn, boolean view,
                            boolean inSchema, LocalDateTime archivedLastSeen) { }
}
