# Update the legacy UI pages to match new formatting

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
- Are the `TableViewComponent` drill-downs (per-column, per-join query lists)
  still used, or can `TableLensComponent` absorb them?
- Can Bootstrap 4 be dropped once legacy templates stop using it (nav bar still
  uses `navbar` markup)?

## Effort: S per page restyled; M–L for the full migration path.
