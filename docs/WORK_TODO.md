
### Update the legacy UI pages to match new formatting

Nearly done — see `docs/plans/legacy-ui.md`. The SQL view, the shared
query-summary pair, `/schemas`, `/tables` and `/tags` are all on the lens
vocabulary; the reports and tag-index pages were removed rather than
restyled; Bootstrap 4 is gone.

What remains is a deletion decision on the last two legacy components,
`TableViewComponent` and `TableGraph*` (ngx-graph), both of which already
have lens replacements.

### Add a `**` catch-all route

An unknown URL matches nothing, renders an empty outlet, and silently resets
the address bar to `/` — indistinguishable from a broken page. `/` itself
redirects to `/tables` correctly; it is only unmatched paths that fall
through. Wants a not-found view, or a redirect.

### Put the tables type filter in the URL

The all/tables/views filter on `/tables` is component state, so "views only"
cannot be shared as a link. It predates the URL rule in CLAUDE.md and now
sits in the same header strip as the URL-backed `?tags=` chips, which makes
the gap obvious. Small: it needs a param name and the same
decode-on-init / `replaceUrl` write-back the tag strip already does.

### Add an MCP server

### Improve the explain plan parser

### Fix duplicate PRIMARY rows from IndexLoadingService

`GET /metadata/table/app_other/groups` returns 10 index rows, two of them named
`PRIMARY` with identical columns (`['id']`, unique). The loader should not
emit the same index twice.

Affects anything reading `TableStructure.indices` — the table lens Indexes
card and the SQL view's relevant-indexes panel both render straight off it.
A presentation-layer dedupe by index name is in place in
`ui/src/app/sql-view/index-relevance.ts` (pinned by
`index-relevance.spec.ts`, "renders a repeated index name once"), so nothing
is visibly broken today. That is a plaster on the symptom; the loader is
still producing rows it shouldn't.

Likely long-standing: the old SqlList/table-view index panels skipped
`PRIMARY` outright, so a duplicated primary key never rendered and the bug
had nowhere to show. Worth checking whether the duplication is confined to
PRIMARY or affects any multi-column/multi-row JDBC index metadata — the
`DatabaseMetaData.getIndexInfo` result set returns one row *per column* of an
index, which is the obvious suspect for a missing grouping step.

Backend investigation; start at `schema/IndexLoadingService.java`.

### Clean all history so queries look anonymous

Scrub older git history

### Improve the way we manage environments in sql-explorer

Brainstorm on this, do we attach them to a user session? Does that require that we add user auth first? Do we add a 
higher level concept to link environments in a dev-test-prod way?

---

## Resolved

### Normalize repeated sql blocks before normalized hashing

Done 2026-08-07. Repeat collapsing shipped earlier; the UI half landed now
as a "merged N variants" expander in the workbench detail — per-child
repeat counts (flagged where they differ from the query shown), executions
and weighted average time, N counting *distinct* perf_schema digests
because a group's members often share one. The digest link became a
self-contained copy button rather than a live lookup. The group is durable:
new Avro `MergedChild` / `mergedChildren`, written to parquet once at the
end of a load instead of per row, which is why groups never survived a
reload before.

### Update the Graph UI to work on any query result or tag result

Done in three passes: tag/search scopes + view edges (graph
generalization); graph presentation state in `var-table`/`var-layers`
(`var-` = page-owned presentation params, legacy `?table=` still read);
and 2026-08-04 the shareable handoff — the *entire* search, including the
schema-tree selections and tag filters, travels in the URL with identical
param names on `/queries` and `/graph?source=search` (`search-url.ts`).
Tag filters share as intent and re-resolve live; the in-memory search
snapshot is gone. Workbench links now also carry the tree scope.

### Tag selection component UI cleanup

Done 2026-08-04: TagViewComponent rebuilt without Material (colored chips,
real ×, wrapping, debounced suggestions); admin source list ported off
mat-selection-list; `@angular/material`/`@angular/cdk` removed entirely.
Root causes: the prebuilt light theme (all-white) and the Material Icons
font never being loaded ("ca" was the clipped `cancel` ligature text).

### Cleanup gradle build

Done 2026-08-04: `dockerSourceCopy`/`dockerLibCopy`/`dockerStage` and the
`jar.finalizedBy(dockerStage)` hook removed from `build.gradle`;
`src/main/docker/Dockerfile` tombstoned pending `git rm`. Compose builds
from the repo-root Dockerfile.

### Support Grafana style expressions in date pickers

Done. Implemented in `ui/src/app/date-range-selector/` — `date-math.ts` parses
`now`, `now-6d`, `now/M`, `now-1M/M`, absolute dates, etc. (covered by
`date-math.spec.ts`), and `DateRangeSelectorComponent` provides the
expression inputs plus quick ranges. Wired into the queries workbench
(first-seen / last-seen filters), which resolves expressions at request time
so relative ranges stay live. No other date inputs exist in the UI.

### Confirm behavior of Admin -> Sources -> Refresh, is this necessary?

Confirmed — yes, it is necessary. The "Refresh Schema" button posts
`/environments/{name}/refresh-schema` → `Environment.refreshSchema()` →
`DatabaseServer.refreshSchema()`, which nulls the cached
`SqlSchemaRepository` per server (the Hikari pool is kept). The schema is
loaded lazily on first use and cached for the lifetime of the JVM — there is
no TTL or automatic invalidation — so this button is the only way, short of a
restart, to pick up DDL changes (new tables/columns/indexes/FKs) from the
analyzed servers.

Caveat: refreshing the schema does **not** re-analyze already-loaded sources.
Column/table resolution happens at import time (`SqlRepositoryManager
.loadFromSqlSources` captures the schema then), so existing analysis reflects
the old schema until sources are re-applied. The admin UI now notes this next
to the button.