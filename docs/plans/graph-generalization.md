# Update the Graph UI to work on any query result or tag result

> **Status: implemented 2026-08-02.**
> Backend: `GraphV2Service.buildGraph(seed, profileFilter, profiles)` is the
> shared core; `getSchemaGraph` keeps schema seeding, new
> `getGraphForProfiles` seeds from the profile set only. New endpoints:
> `GET /graph/v2/by-tag/{tag}` (via `SqlTagsService.getSqlByTag`) and
> `POST /graph/v2/search` (via new `QuerySearchService.matchingProfiles` —
> unsorted/unpaged twin of `search`, limit/offset ignored). The deferred
> view→base edges landed here: `GraphV2` gains `viewEdges` +
> `Node.view`; a displayed view *pulls its base tables into the graph*
> (recorded choice — unlike FK edges, which only link already-displayed
> nodes, since lineage is the point of the edge).
> Frontend: `GraphLensComponent` gets a source mode driven by the route
> (`?tag=` → tag, `?source=search` → workbench snapshot, else `:schema`);
> view→base edges draw as dashed purple arrows with a `views` layer toggle
> + legend row (shown only when present) and view nodes carry a purple
> corner label. Entry points: "graph results ↗" in the workbench toolbar
> (snapshots the current filters into `QueryScopeService.setSearchSnapshot`
> — **recorded choice: snapshot, not live-follow**, so the graph doesn't
> re-layout under the user; re-click to refresh) and "graph ↗" per tag in
> the tag manager.
> Remaining from the plan's open questions: the v1 cross-schema join
> report (`/graph/by-tag/{tag}/{schema}/join/{otherSchema}`) is not ported —
> it stays on v1 until the legacy-ui item decides its fate.

## Current state

Two backend graph stacks:

- **v1** (`graph/SqlGraphResource.java`): `/graph/all`, `/graph/schema/**`,
  `/graph/by-tag/{tag}` (+ cross-schema report). Already tag-aware. Consumed
  only by the *legacy* ngx-graph `TableGraphLoaderComponent`
  (`main.ts:149-177`) — note TECH_DEBT's "no frontend caller" claim for v1 is
  wrong.
- **v2** (`graph/GraphV2Resource.java:18-21`): `GET /graph/v2/schema/{schema}`
  only. `GraphV2Service.getSchemaGraph` (`GraphV2Service.java:41-124`) does one
  pass over `getAllAnalyzedSql()` with a schema predicate at `:57-61` — **the
  only filter**. The modern canvas `GraphLensComponent` (531 lines, hand-rolled
  force layout) consumes it; a `?table=` query param highlights but doesn't
  filter.

Query results: `POST /queries/search` (`profiles/QuerySearchService.java:60-93`)
applies reusable predicates (`matchesQuery`/`matchesNonScope`/`matchesScope`)
over the same profile stream. The search *response* can't drive a client-side
graph — the typed frontend `QueryProfile` (`query-search.service.ts:78-89`)
lacks `tables[]`/`joinColumns[]`, and facets give nodes but no edges. Tag
results: `SqlTagsService.getSqlByTag` (`commentary/SqlTagsService.java:28-34`)
already returns full profile sets.

## Proposed approach

Backend: refactor `GraphV2Service.getSchemaGraph` to
`buildGraph(Collection<SqlProfile> profiles, …)` — the schema predicate is the
only schema-specific part; node seeding switches from "all tables in schema" to
"tables referenced by the profile set". Add
`POST /graph/v2/search` accepting a `QuerySearchRequest` (reuse
`QuerySearchService` predicates) and `GET /graph/v2/by-tag/{tag}` (reuse
`SqlTagsService.getSqlByTag`), both returning the existing `GraphV2` payload —
no frontend payload changes needed. Test seam already exists
(`GraphV2ServiceTest`).

Frontend: give `GraphLensComponent` a source mode (`schema | tag | search`).
For search mode, subscribe to the shared `QueryScopeService`
(`ui/src/app/query-scope.service.ts`) so the graph reflects the current
workbench filter, and add a "graph these results" button in the workbench
(none exists today, `queries-workbench.component.html:196-240`). This also
retires the last reason to keep the legacy v1 graph pages (ties into
[legacy-ui.md](legacy-ui.md)).

## Open questions

- Should the graph auto-follow the live workbench scope or snapshot the filter
  when opened?
- Keep v1 endpoints for the cross-schema join report, or port that too?

## Effort: M. Backend refactor is small; frontend mode plumbing is most of it.
