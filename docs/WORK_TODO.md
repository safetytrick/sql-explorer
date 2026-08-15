
### Retire the last two legacy UI components

`TableViewComponent` and `TableGraph*` (ngx-graph) are the only legacy
components still routed, and both already have lens replacements. This is a
deletion decision, not styling work — see `docs/plans/legacy-ui.md`.
`SmallTableViewComponent` has no consumers and goes with them.

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

Shipped — awaiting Michael's hands-on testing before this closes.

### Improve the explain plan parser

Shipped — awaiting Michael's hands-on testing before this closes.

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
