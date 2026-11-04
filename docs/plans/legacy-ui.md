# Finish the lens migration: retire the last legacy components

Nearly done. What remains is one deletion decision, not styling work.

## What is left

**Retire `TableViewComponent` and `TableGraph*`, or keep them?**

Both are still routed — `/table/:s/:n/queries` plus the `/join/**` and
`/:column` variants, and `/table-graph/**` — and both already have lens
replacements. `TableLensComponent` absorbed the table view's queries list and
its column and join drill-downs (2026-08-08); `GraphLensComponent` on the v2
graph API replaces the ngx-graph view.

Open sub-questions:

- Does anything still link to either? (Nav bar, in-app links, bookmarks.)
- Does `@swimlane/ngx-graph` go with `TableGraph*`? It is the source of the
  non-ESM `dagre`/`webcola` build warnings, so dropping it is a visible win.
- The v1 graph API (`graph/SqlGraphService`, `/graph/all`, `/graph/schema/**`,
  `/graph/by-tag/**`) has no confirmed frontend caller once `TableGraph*` is
  gone — verify server-side usage before deleting it too.

`SmallTableViewComponent` has had no consumers since the SQL view restyle and
is a free deletion whenever the others are decided.

## Why the drill-downs are safe to delete

The one thing `TableViewComponent` had that the lens did not was the
per-column and per-join query lists. Those were ported deliberately rather
than dropped:

- The lens **Queries** card is driven by `POST /queries/search` scoped to the
  table — sorted, paged, faceted. Not the legacy `Table` payload, which is
  400–700 KB of unsorted, unpaged profiles for a busy table.
- **Column drill-down** uses the `columns` filter on `QuerySearchRequest`,
  delegating to `SqlProfile.constrainedBy`, so it means what the table view
  always meant: the column appears in a **join or the criteria**, never a bare
  select. Only join/criteria columns are clickable — a select-only column
  would open an empty list that reads as broken.
- **Join drill-down** is `tables=a,b&tableMode=ALL`. The search API already
  means "references all of them", verified equal to the legacy join endpoint
  (116 = 116 on `$schema.table` × `$schema.$table`).

All of it is URL state in the shared `search-url.ts` vocabulary, so "open in
workbench" is a param translation rather than a hand-mapping.

## Effort: S — one deletion pass, once the call is made.
