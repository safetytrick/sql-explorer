# Update the legacy UI pages to match new formatting

> **Mostly done (2026-08-15).** What is left is one deletion decision, not
> styling work. Shipped since the 2026-08-08 block below:
>
> - **`SqlViewComponent` restyled** to the lens vocabulary — cost and index
>   fit above the fold, SQL at 3/4 width beside a relevant-indexes panel
>   scored on whether the query constrains each index's *leading* column.
> - **Reports pages removed.** `SqlListComponent` and its
>   `/analyzed-sql/reports/*` routes are gone rather than restyled; menu
>   access had already been dropped.
> - **Tag index resolved by folding, not restyling.** Its per-type links
>   became the counts in the tag manager, and the standalone page retired.
> - **Bootstrap 4 dropped entirely** — see the answered question below.
> - **`sql-summary` / `small-sql-view` restyled**, and a latent crash fixed
>   in both: they read `join.left.table…` off what is really a `[string,
>   string]` pair.
> - **`/schemas` and `/tables` rebuilt on the Ledger** — dense rows, pinned
>   header, duration bars. These two are no longer legacy pages. (Their brief
>   unstyled spell was self-inflicted: they were the last templates riding
>   Bootstrap's `.table`, and dropping Bootstrap took it out from under them.)
> - **`TagField`** replaced `app-tag-view` everywhere, and the tag filter
>   strip and **`/tags` master/detail** landed on top of it.
>
> **Still open:** `TableViewComponent` and `TableGraph*` (ngx-graph). Both
> have lens replacements; both are still routed. See "What is left" at the
> bottom.

> **Table view: answered and in progress (2026-08-08).** The open question
> below — "are the `TableViewComponent` drill-downs still used, or can
> `TableLensComponent` absorb them?" — is answered: **absorb**. Michael also
> chose to host the join view *in the lens* rather than link out to the legacy
> route, and to port the remaining parity items rather than drop them.
>
> Shipped in this pass:
> - A **Queries** card on the lens, driven by `POST /queries/search` scoped to
>   the table — sorted, paged, faceted. Not the legacy `Table` payload, which
>   is 400–700 KB of unsorted, unpaged profiles for a busy table.
> - **Column drill-down.** New `columns` filter on `QuerySearchRequest`
>   delegating to `SqlProfile.constrainedBy`, so it means exactly what the
>   table view has always meant: the column appears in a **join or the
>   criteria**, never a bare select. Only join/criteria columns are clickable —
>   `ColumnUsage.roles` already says which, and a select-only column would open
>   an empty list that reads as broken.
> - **Join drill-down, lens-hosted**, as `tables=a,b&tableMode=ALL`. That
>   needed no backend work: the search API already means "references all of
>   them", verified equal to the legacy join endpoint (116 = 116 on
>   `app_other.group_member` × `app_other.groups`).
> - **FK and co-queried collapsed by default** to name-only links that trigger
>   the join drill-down; expanded state in `var-fk` / `var-co`.
> - Parity: the **DDL** panel, table **remarks**.
>
> All of it is URL state in the shared `search-url.ts` vocabulary, per the
> convention in CLAUDE.md — so "open in workbench" is a pure param
> translation rather than a hand-mapping.
>
> **Not done here:** `TableViewComponent` is still routed and still reachable
> at `/table/:s/:n/queries`. Retirement is a separate step, once the two have
> been compared side by side.

## Current state

`docs/TECH_DEBT.md` (top-priority row 7, §2) frames this as the half-finished
"lens" migration: legacy `table-view` / `table-graph(-loader)` / `schema-list` /
`sql-view` coexist with `*-lens` replacements, both routed (`ui/src/main.ts:42-204`
route table). Two HTTP services (`analyzed-sql.service`, `schema-lens.service`)
hit overlapping endpoints.

Formatting delta: legacy pages use Bootstrap 4 markup (`.card`, `table.table`,
`btn btn-primary`) repainted by shims in `ui/src/styles.css:218-238`, plus five
remaining `.css` files; the new idiom is component-scoped SCSS over the CSS
design tokens (`styles.css:7-47`) with the vocabulary established in
`table-lens.component.scss` (`.view-header`, `.lens-card`, `.card-head`,
`.data-table`, `.badge`, `.mono`, ghost `.btn`). Legacy pages still routed and
linked from the nav bar: `/schemas` (SchemaListComponent), `/tables`
(TableNamesComponent), `/table/:s/:n/queries` (+2 variants, TableViewComponent),
`/analyzed-sql/:id` (SqlViewComponent), `/analyzed-sql/reports/*`
(SqlListComponent), tag views, `/table-graph/**` (ngx-graph, v1 API).

## Interpretation / options

Two very different reads:

- **(a) Restyle in place** — keep the legacy components, rewrite their templates
  and styles to the lens vocabulary. Preserves the per-column/per-join query
  drill-downs that only `TableViewComponent` offers today.
- **(b) Finish the migration** — TECH_DEBT's preferred end state: port any
  unique features into the lens components, then delete the legacy set, their
  routes, the `styles.css` legacy shims, and possibly ngx-graph + Bootstrap.

## Proposed approach

Do (a) cheaply for pages with no lens replacement (`SqlViewComponent`,
`SqlListComponent`, tag views, `TableNamesComponent`, explain view) — mostly
template class swaps to the lens vocabulary. Treat `TableViewComponent` /
`table-graph` / `SchemaListComponent` as migration targets per TECH_DEBT
sequencing instead of restyling them. Start with one page
(`SqlViewComponent` is the most-visited legacy page) to lock the pattern.

## Open questions

- ~~Is deleting legacy pages on the table now, or is restyling wanted first?~~
  **Answered in practice: both, page by page.** Pages with a lens replacement
  were deleted (reports, tag index); pages without one were restyled
  (`sql-view`, `sql-summary`) or rebuilt (`/schemas`, `/tables`).
- ~~Are the `TableViewComponent` drill-downs (per-column, per-join query lists)
  still used, or can `TableLensComponent` absorb them?~~ **Answered: absorbed
  into the lens — see the status block above.**
- ~~Can Bootstrap 4 be dropped once legacy templates stop using it (nav bar
  still uses `navbar` markup)?~~ **Answered: yes, and it is done** (`53d358f`).
  `bootstrap`, `bootstrap-icons`, `jquery` and `popper.js` are out of
  `package.json`, the `scripts` array in `angular.json` is empty, and the
  `@import` plus the legacy `.card`/`.table`/`.list-group-item` shims are gone
  from `styles.css`. The scope was smaller than it looked — cross-referencing
  Bootstrap-shaped class names against classes our own SCSS defines put the
  real count at six templates, not fourteen. The nav bar needed actual work:
  its Admin dropdown ran on `data-toggle="dropdown"`, so it was rewritten as
  component state with outside-click and Escape handling.

## What is left

Two components, one decision: **retire `TableViewComponent` and
`TableGraph*`, or keep them?** Both are still routed
(`/table/:s/:n/queries` + 2 variants, `/table-graph/**`) and both have lens
replacements. This is a deletion, not a restyle — the remaining questions are
whether anything still links to them and whether the ngx-graph dependency
(`@swimlane/ngx-graph`, plus its non-ESM `dagre`/`webcola` build warnings)
goes with `TableGraph*`.

`SmallTableViewComponent` has had no consumers since the SQL view restyle and
is a free deletion whenever the others are decided.

Unrelated to the migration but surfaced by it: there is no `**` catch-all
route, so an unknown URL renders an empty outlet and resets the address bar
to `/`.

## Effort: remaining work is S — one deletion pass, once the call is made.
