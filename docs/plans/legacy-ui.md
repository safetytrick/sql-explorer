# Update the legacy UI pages to match new formatting

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

- Is deleting legacy pages on the table now, or is restyling wanted first?
- ~~Are the `TableViewComponent` drill-downs (per-column, per-join query lists)
  still used, or can `TableLensComponent` absorb them?~~ **Answered: absorbed
  into the lens — see the status block above.**
- Can Bootstrap 4 be dropped once legacy templates stop using it (nav bar still
  uses `navbar` markup)?

## Effort: S per page restyled; M–L for the full migration path.
