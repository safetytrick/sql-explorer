# Schema Lens — Gap Analysis & Implementation Plan

`docs/schema-lens.html` is a static mockup of the UI SQL Explorer should grow into:
a dense, dark, four-view workbench (Schema / Queries / Graph / Tags) over the data
the analysis pipeline already produces. This document inventories the mockup's
features, maps each one to what the backend already has, lists the endpoints and
model changes needed to fill the gaps, and phases the work.

## Feature inventory (from the mockup)

### Shell
- Topbar: brand, **connected database + connection status** (→ our environment
  selector), nav tabs Schema / Queries / Graph / Tags.
- Persistent left sidebar: schema → table tree with per-table **column count** and
  an **amber warning dot** (analyzer findings), with a text filter.

### Schema view (table detail)
- Stat strip: distinct queries, total executions, total query time, **read/write %**.
- Foreign keys card: direction badge (out/in), **constraint name**, local column
  (+ type-mismatch badge, e.g. `INT≠BIGINT`), referenced table.column,
  **ON DELETE / ON UPDATE rules**; row click navigates to the referenced table.
- "Commonly queried together" card: co-occurring table, #queries, executions,
  avg time, **join types (INNER/LEFT)**, FK-relationship badge.
- Columns card: name (+ warn badge), type, attribute badges (PK / FK / AI /
  UNSIGNED), charset/collation, nullable, **query-role pills
  (join / criteria / select / group by)**, per-column query count with a mini bar;
  quick filters **all / used / keys**.
- Right rail: index list (PK/IDX badge, name, columns), table tags.

### Queries view
- Filter sidebar:
  - Activity: any / active 30d / dormant; first-seen & last-seen date ranges.
  - Tags: match any/all, chip input with suggestions, "untagged only".
  - Performance ranges: avg time, max time, avg rows scanned,
    **selectivity (sent/scanned)**, rows sorted.
  - Execution flags (tri-state any/yes/no): full table scan, temp tables,
    temp-to-disk, errors, warnings.
  - Analyzer: has warnings / analysis failed / clean.
- Active-filter chip bar, result count, sort selector (avg time, max time,
  rows scanned, selectivity, last seen).
- Query rows: truncated SQL with keyword highlight, badges
  (active/dormant, full scan, tmp disk/table, Nerr, Nwarn, analyzer!, failed),
  metric strip (avg/max/scanned/sel/last), tag chips.
- Detail pane: full SQL; analyzer warning / failure banners; **both digests**
  (perf_schema + app-normalized); counts (executed, errors, warnings); time
  (total/max/avg); rows (sent & scanned, total & avg); temp tables (count/disk);
  sorting (rows sorted, merge passes); activity (first/last seen, status); tags.

### Graph view
- Canvas with pan/zoom/wheel-zoom, minimap with viewport box, hover tooltip
  (FK in/out lists, query & column counts), click-through to table view.
- Layer toggles: **FK edges** (solid, labeled `col→col`, amber when types
  mismatch), **co-query edges** (dashed, labeled with execution counts),
  **self-joins** (loop glyph). Legend.
- Nodes show schema · column count · query count; warn tables outlined amber.

### Tags view (manager)
- All-tags table: **colored chip**, query count, table count, **last used**, delete.
- Create tag: name, **color picker**, live preview.
- Colored chips reused everywhere (query rows, filters, table detail).

## What the backend already has

The Avro model (`models/src/main/avro/SqlExplorer.avdl`) already covers nearly the
whole detail pane and filter sidebar:

| Mockup element | Existing field |
|---|---|
| executions, errors, warnings | `RuntimeMetrics.executed/errors/warnings` |
| total / max / avg time, lock time | `RuntimeMetrics.totalTime/maxTime/averageTime/lockTime` |
| rows sent & scanned (total + avg) | `RuntimeMetrics.rowsSent/averageRowsSent/rowsScanned/averageRowsScanned` |
| selectivity | derivable: `averageRowsSent / averageRowsScanned` |
| temp tables / to disk | `RuntimeMetrics.tempTables/tempDiskTables` |
| rows sorted, merge passes | `RuntimeMetrics.rowsSorted/sortMergePasses` |
| full table scan badge/filter | `RuntimeMetrics.fullTableScan` |
| first/last seen, active/dormant | `RuntimeMetrics.firstSeen/lastSeen`, `SqlProfile.queryActivityStatus` |
| perf_schema + app digests | `SqlProfile.digest` / `normalizedDigest` (+ `digestList`) |
| analyzer warning / failed / clean | `SqlProfile.mismatchedJoins` / `analysisFailed(+Reason)` / neither |
| read vs write | `SqlProfile.type` / `isDml` |
| column roles join / criteria | `SqlProfile.joinColumns` / `criteriaColumns` |
| charset/collation, nullable, AI | `schema.TableColumn` (via `ColumnEncodingService`) |
| indexes | `IndexLoadingService` / `/metadata/table/{s}/{t}` |
| FK columns | `schema.ForeignKey` (from/to `ColumnReference`s) |
| tags on queries & tables | `commentary.Tag` (path-based), `/tags*` endpoints |
| co-occurrence graph | `SqlGraphService` `/graph/*` (threshold, depth, by-tag) |
| environment/topbar db | `/environments` + `X-Sql-Explorer-Environment` (done) |

Existing REST surface: `/schemas`, `/tables(/{schema})`, `/table/{s}/{t}`,
`/table/{s}/{t}/column/{c}`, `/table/{s}/{t}/join/{s2}/{t2}`, `/analyzed-sql/{id}`,
`/tables/with-column/{name}`, `/metadata/table/{s}/{t}(/create)`, `/graph/*`,
`/tags*`, `/notes*`, `/sources*`, `/environments*`.

## Gaps

### A. Model / analysis gaps (require avdl evolution or loader changes)

1. **FK metadata is lossy.** `ForeignKey` keeps only from/to columns.
   `ForeignKeyLoadingService` already reads `UPDATE_RULE`/`DELETE_RULE` (uses them
   for ordering, then discards) and never keeps the constraint name. Add
   `constraintName`, `onUpdate`, `onDelete` to the record. Incoming FKs need a
   reverse index in `LoadedSchema` (references-to-table lookup).
2. **FK type-mismatch flag.** Compare local vs referenced `TableColumn` types at
   schema load (`INT≠BIGINT` badge); reuses the comparison logic family of
   `JoinAcrossTypesAnalyzer`.
3. **Column roles `select` and `group by`.** Analysis captures join + criteria
   only. New avdl fields `selectColumns` / `groupByColumns` on `SqlProfile` plus
   two analyzers (select-list expansion needs `*` handling via the resolver).
4. **Join type.** `JoinColumns` has no INNER/LEFT/RIGHT. Add `joinType` field,
   captured in `SqlAnalyzer` from jsqlparser's join model. Powers the co-queried
   card and graph edge labels.
5. **Tag colors + usage stats.** `Tag` entity has no color. Add a `color` column
   (Liquibase, shared-tags schema), and expose usage counts (queries/tables) and
   last-used (max `createdOn` per name).
6. **Per-table analyzer-warning rollup.** The sidebar dot, graph amber outline,
   and stat strip need "this table has ≥1 query with mismatched joins /
   failures" — derivable per request from profiles; no model change, just
   aggregation.
7. Self-joins: derivable (`joinColumns` where left.table == right.table); no
   model change.

### B. Endpoint gaps

1. **`POST /queries/search`** — the biggest gap; there is currently no way to list
   queries except through a table. Body: metric ranges (avgTime, maxTime,
   avgRowsScanned, selectivity, rowsSorted), tri-states (activity, fullScan,
   tmpTable, tmpDisk, errors, warnings), firstSeen/lastSeen date ranges, tags
   (mode any/all, untagged), analyzer state (warn/fail/clean), sort key +
   direction, limit/offset. Returns rows shaped for the list (id, sql, badges,
   metrics, tags) + total count. Implementation: stream filter over the active
   environment's `SqlRepository.getAllAnalyzedSql()` — all predicates map 1:1 to
   existing fields (table above).
2. **`GET /schema-tree`** — one call for the sidebar: schemas → tables with
   column count (from schema metadata), query count (from profiles), warn flag
   (gap A6). Merges what `/tables` + `/metadata` know today.
3. **`GET /metadata/table/{s}/{t}/foreign-keys`** — enriched FKs, both directions
   (out + in), with constraint name, rules, per-column type-mismatch flag (A1/A2).
4. **`GET /table/{s}/{t}/co-queried`** — co-occurring tables with query count,
   total executions, avg time, join types (A4), has-FK flag. The pairwise data
   exists in `SqlGraphService`/`/table/../join/..`; this rolls it up per partner
   table.
5. **`GET /table/{s}/{t}/columns`** — merged metadata + usage per column: type,
   PK/FK/AI/unsigned attrs, charset/collation, nullable, role pills, query count.
   (Today the UI would need `/metadata/table` + one `/column/{c}` call per column.)
6. **Table stat strip** — extend the `/table/{s}/{t}` response (or a small
   `/table/{s}/{t}/stats`): distinct queries, total executions, total time,
   read/write split (over `isDml`/`type`).
7. **`GET /tags`** (manager) — tag definitions with color + usage counts + last
   used; `POST /tags` gains color; `DELETE /tag/{id}` exists.
8. **Graph payload v2** — extend `/graph/schema/{schema}` (or add `/graph/v2/...`)
   with: FK edges (col→col label, mismatch flag), co-query edges with execution
   counts, self-join flags, per-node column counts + warn flag. Today's payload is
   co-occurrence-centric.

All new endpoints are environment-scoped for free via `EnvironmentContext` (the
UI interceptor already sends the header).

### C. UI gaps

The mockup is a different shell from today's Angular app (navbar + routed pages):
dark IBM-Plex theme, top nav with four views, persistent tree sidebar,
three-pane queries workbench, custom canvas graph with minimap. Today's app has
schema list, table view (Material cards), analyzed-sql view, ngx-graph view.

## Plan

### Phase 1 — Read-side endpoints over existing data (backend only)
`POST /queries/search`, `GET /schema-tree`, `GET /table/../co-queried`,
`GET /table/../columns`, table stat strip, per-table warn rollup.
No avdl changes; pure aggregation in `SqlRepositoryService`/new services + tests.
This unblocks most of the Queries view and half the Schema view immediately.

### Phase 2 — Schema-metadata enrichment
FK constraint names + rules + reverse index + type-mismatch flag (A1–A2);
`GET /metadata/table/../foreign-keys`. Loader + `LoadedSchema` change,
`refresh-schema` picks it up per environment.

### Phase 3 — Analysis/model evolution (avdl)
`selectColumns`, `groupByColumns`, `JoinColumns.joinType` (A3–A4) + analyzers +
re-import to backfill. Schema evolution is additive (defaults), so old parquet
exports stay readable.

### Phase 4 — UI shell + Schema view
New layout component (topbar tabs, tree sidebar from `/schema-tree`), dark theme
(port the mockup's CSS custom properties into the Angular theme), table detail
page composed of the Phase 1–2 cards. Keep existing routes working; the tree and
tabs are a shell around them (`/table/:schema/:name` stays the Schema view URL).

### Phase 5 — Queries workbench
Filter sidebar (tri-state buttons, dual-range sliders, date inputs, tag chip
input with suggestions from `/tags/names`), active-filter chips, sortable
virtual-scrolled list, detail pane — all against `POST /queries/search`.
Filter state in the URL (query params) so views are shareable.

### Phase 6 — Graph v2
Canvas renderer (pan/zoom/minimap/tooltip/click-through, FK + co-query + self-join
layers, mismatch coloring) against the graph v2 payload. Replaces ngx-graph for
this view (ngx-graph stays for the legacy route until parity).

### Phase 7 — Tag manager
Color column + migration, `GET /tags` stats endpoint, manager page (create with
color picker/preview, usage table, delete), colored chips adopted across views.

## Open questions
- **Activity window**: mockup's "active 30d" — `queryActivityStatus` is computed at
  import; decide whether the search endpoint recomputes from `lastSeen` against a
  configurable window instead.
- **Tag identity**: tags are path-based rows, one row per assignment; the manager
  implies tag *definitions* (name+color) — likely a new `tag_def` table keyed by
  name, referenced by assignments.
- **Read/write %**: by distinct query or weighted by executions? (Mockup implies
  executions-weighted.)
- **Search paging**: in-memory repositories make offset paging cheap; revisit if
  repositories move off-heap.
