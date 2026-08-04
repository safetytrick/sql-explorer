# Graph UI: schema/table selection in URL parameters

Follow-up to the graph generalization (tag/search scopes + view edges —
shipped; see git history of `graph-generalization.md`). WORK_TODO asks:
move schema and table selection into url parameters, and raises the
question of a `var-` prefix so pages have a clean namespace for url params.

## Current state

- The graph already keeps *some* state in the URL: `/graph/:schema` (route
  param), `?table=` (highlight), `?tag=` and `?source=search` (scope modes)
  — `graph-lens.component.ts` `applyRoute()`. But the schema `<select>`
  navigates (full route change), the highlighted table isn't updated when
  clicking nodes, and layer toggles (fk/co/self/views) + zoom are lost on
  reload/share.
- The **workbench** already has the repo's URL-state pattern to mirror:
  `readFiltersFromUrl()` + `router.navigate([], { queryParams, replaceUrl: true })`
  (`queries-workbench.component.ts:507-…`), round-tripping filters as query
  params.
- The `search` scope mode is **not** URL-expressible (it reads the
  in-memory `QueryScopeService.getSearchSnapshot()`), so a shared
  `/graph?source=search` link degrades to unconstrained for the recipient.

## Proposed approach

1. Adopt the workbench's pattern in `GraphLensComponent`: reflect
   `schema` (already route), `table`, `layers`, and `tag` into query
   params with `replaceUrl: true`; restore in `applyRoute()`. Selection
   clicks update `?table=` instead of only component state.
2. On the `var-` prefix question — recommendation: **yes, adopt it for
   graph-owned params** (`var-table`, `var-layers`), grafana-style, so
   page-owned variables can't collide with cross-cutting params
   (`source`, environment-style params, future global ones). But apply it
   *consistently or not at all*: the workbench's existing filter params are
   unprefixed and widely bookmarked — either migrate both surfaces with a
   back-compat read of the old names, or accept the graph as the first
   adopter and document the convention.
3. `source=search` sharing: either serialize the snapshot request into a
   compact param (`var-q=<base64 json>` — ugly but shareable) or accept
   non-shareability and mark the mode chip "session only". Proposal:
   session-only for now; revisit if shared graph links get real use.

## Open questions

- `var-` migration scope: graph-only first, or workbench too (with legacy
  param names still read)?
- Should layer toggles really persist? (They're cheap to re-click;
  persisting zoom/pan is likely over-engineering — proposal: persist
  layers + table + tag, not viewport.)

## Effort: S–M (the pattern exists; the convention decision is the real work).
