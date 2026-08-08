# Graph URL state v2: a shareable workbench→graph handoff

> **Status: v2 implemented 2026-08-04.** Michael's decisions on the open
> questions — all four as recommended: (1) tag filter shared as **intent**,
> (2) scope params on **both** `/queries` and `/graph`, (3) **plain csv**
> for `tables`, (4) in-memory snapshot **deleted**. Implementation:
> `search-url.ts` is the shared codec (encode/decode, `resolveSchemaTagScope`,
> `buildSearchRequest` — the workbench's URL and request-assembly logic
> extracted whole, plus `schemas`/`tables`/`tableMode`/`schemaTags`/
> `schemaTagsExclude`/`schemaTagMode`). The workbench seeds
> `QueryScopeService` from the URL (tree checkboxes/chips light up on a
> shared link), writes scope changes back (`urlReady` guard prevents the
> BehaviorSubject init emissions from wiping params), and `graphResults()`
> is a pure param translation. The tree republishes its tag scope on
> external filter seeding. The graph decodes the same vocabulary, resolves
> tag intent via `getTagsForPaths`, and POSTs the assembled request; its
> own `var-` writes became merge patches (`graphOwnedParamPatch`) so they
> can't disturb co-resident search params; the search scope key is the
> normalized param set. v1's `var-table`/`var-layers`/legacy `?table=`
> unchanged. Specs: `search-url.spec.ts`, workbench seeding spec
> (RouterTestingHarness), extended `graph-url.spec.ts`.

## Inventory: what the search request is made of today

`buildRequest()` (`queries-workbench.component.ts:175`) assembles the
`QuerySearchRequest` from two sources:

**Already URL-encoded** (workbench `write/readFiltersFromUrl` vocabulary):
`q`, `activity`, `queryTypes`, `firstAfter/firstBefore/lastAfter/lastBefore`
(live date-math expressions), `tags`, `tagMode`, `untagged`, the five
min/max ranges, the seven tri-state flags, the four analysis booleans,
`failKind`, `sort`.

**Session-only, never URL-encoded** (lives in `QueryScopeService`, edited
by the schema tree):

| State | Feeds request field |
|---|---|
| `scope.schemas` — schema checkboxes | `schemas` (∩ tag-included) |
| `scope.tables` — `schema.table` keys | `tables` |
| `scope.tableMode` — ANY/ALL | `tableMode` |
| `schemaTagFilter` — include/exclude tags + mode | resolved via the tags-by-schema map into `schemas` ∩ / `excludeSchemas` |

The graph handoff (`graphResults()`) stores the built request in an
in-memory snapshot; `/graph?source=search` reads it back. A shared link
carries none of it — that is the gap.

Backend: `POST /graph/v2/search` already accepts the full
`QuerySearchRequest` (limit/offset ignored). **No backend change needed.**

## Proposed design

1. **Shared codec.** Extract a pure `search-url.ts` (analogous to
   `graph-url.ts`): `encodeSearchParams`/`decodeSearchParams` covering the
   existing workbench vocabulary *plus* the missing scope params —
   `schemas` (csv), `tables` (csv of `schema.table`), `tableMode` (only
   when `ALL`), `schemaTags` / `schemaTagsExclude` / `schemaTagMode` (the
   tree's tag filter). Also extract `resolvedSchemas()` /
   `resolvedExcludeSchemas()` as pure functions taking the tags-by-schema
   map, so both pages resolve identically.
2. **Workbench adopts it symmetrically.** The workbench URL gains the
   same scope params: seed `QueryScopeService` from the URL at init (the
   tree renders from the service, so its checkboxes/chips reflect a shared
   link automatically), write on `scope$`/`schemaTagFilter$` changes with
   `replaceUrl`. Side effect: workbench links themselves become fully
   shareable, and tree selections survive reload — the same bug, fixed in
   both places with one codec.
3. **The handoff becomes a URL translation.**
   `graphResults()` → `navigate(['/graph'], { queryParams: { source: 'search', ...encodeSearchParams(state) } })`.
   With identical param names on both pages, `/queries?...` ↔
   `/graph?source=search&...` differ only in path — hand-editable. Delete
   the in-memory snapshot (`set/getSearchSnapshot`); the URL *is* the
   snapshot. The "session" wording on the mode chip goes away.
4. **Graph decodes and fetches.** `source=search` mode decodes the params,
   resolves the schema-tag filter against `getTagsForPaths` (same helper),
   builds the request, POSTs as today. Scope-change guard keys on the
   encoded search params so selection/layer clicks still don't refetch.
5. **Namespaces, clarified.** With the params being the search request
   itself, the v1 convention sharpens rather than reverses: **unprefixed =
   cross-page search vocabulary** (identical meaning on `/queries` and
   `/graph`), **`var-` = page-local presentation** (`var-table` highlight,
   `var-layers` — unchanged, so nothing shipped in v1 breaks).

## URL length

`tables` is the only unbounded param. MySQL identifiers cap at 64 chars;
a realistic 10–20-table selection is 300–1300 bytes — comfortably inside
the ~2k conservative / 8k typical browser-and-proxy budget. Selecting
"most of a schema" is better expressed as the one-token schema checkbox
anyway. Alternatives if real scopes ever blow the budget: a compressed
base64 blob (≈50% smaller, but opaque and kills hand-editing) or a
server-side named snapshot (`/graph?snapshot=<id>` — short and durable,
but adds persistence, lifecycle/GC questions, and breaks "the URL is the
state"). **Recommendation: plain csv now; named snapshots only as a
future feature if usage demands it.**

## Back-compat

- `var-table` / `var-layers` / legacy `?table=`: untouched.
- Old `/graph?source=search` links without params previously degraded to
  an unconstrained graph for the recipient; after v2 an empty param set
  decodes to an empty request — the same degradation, no special casing.
- Workbench: existing param names keep their exact meaning; scope params
  are additive.

## Sequencing

1. `search-url.ts` codec + pure schema-resolution helpers, with specs
   (encode/decode/round-trip, resolution against a tag map).
2. Workbench: seed scope from URL, write scope params (the `scope$`
   subscription already exists; writes are `replaceUrl`, so the one
   redundant write during seeding is harmless).
3. `graphResults()` translation; delete the snapshot plumbing.
4. Graph `source=search` decode + tag-map resolution + fetch guard.

## Open questions for Michael

1. **Intent vs. resolved for the tree's tag filter.** Encode the filter
   itself (`schemaTags=core&schemaTagMode=ALL`, re-resolved against the
   live tag map when the link opens — stays current as tags evolve, and
   the tree shows the filter chips) or the resolved schema lists (frozen
   at share time)? *Recommendation: intent — consistent with date
   expressions like `now-7d`, which already share as intent.*
2. **Workbench symmetry.** Add the scope params to `/queries` URLs too
   (shareable workbench links, selections survive reload), or graph-only?
   *Recommendation: both — it's the same codec, and asymmetry would mean
   a shared workbench link still silently drops the tree scope.*
3. **`tables` encoding.** Plain csv vs. compressed blob vs. server-side
   snapshot id. *Recommendation: plain csv (see URL length above).*
4. **Snapshot removal.** Delete `set/getSearchSnapshot` outright, or keep
   it one release as a fallback when `source=search` arrives with no
   params? *Recommendation: delete — after v2 the handoff always emits
   params, and the no-param case has a well-defined (unconstrained)
   meaning.*

## Effort: M (codec + two page integrations + seeding; no backend work).
