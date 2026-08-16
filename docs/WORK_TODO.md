
### Audit SqlProfile field derivations for path-split

Three bugs in one session shared a shape: a field set on a **conditional**
path — an analyzer that can be skipped, throw, or be bypassed — sitting beside
a consistency-linked partner derived in the **always-run**
`SqlProfileExtension.complete()`. Nothing made the pair agree, so they drifted
and the disagreement shipped.

The instances, as the pattern to look for:

- **Null column arrays.** `selectColumns` / `groupByColumns` / `schemas` are
  filled by lazy add-extensions that create the list on first insert, while
  `joinColumns` / `mismatchedJoins` get unconditional setters. An analyzer that
  found nothing, threw, or never ran left null — and `MergedSqlProfile`, which
  is built with `new` rather than the Avro builder, inherited those
  uninitialised fields for the getters it had not overridden. ~5% of profiles;
  crashed the SQL view.
- **`isMultiSchema` and `sourceFileName` on `MergedSqlProfile`.** Same class,
  hidden better: primitives and strings return `false`/`null` rather than
  throwing, so a merged profile silently claimed one schema.
- **Skip-path multi-schema.** `schemas` derived from the tables in
  `complete()` for every profile; `isMultiSchema` set only by `SchemaAnalyzer`,
  which does not run when analysis is skipped. A skipped cross-schema statement
  listed two schemas and claimed one.

`SqlProfileInvariantsTest` now asserts the cross-field invariants against every
construction path, so a **regression** fails a test. This item is the other
half: the deliberate read-through to find derivations that are still split, and
move them into `complete()` — the one place every profile passes through —
rather than waiting for each to surface as a bug.

- Walk each `SqlProfile` field and ask: what sets it, can that code not run,
  and is anything else derived from the same source?
- `complete()` is the right home for anything derivable from another field.
  Deriving there is what makes it hold for skipped, failed and merged profiles
  as well as analysed ones.
- Two invariants are already known-failing and are `@Disabled` in the suite
  with the finding recorded — see the item below on `schemas` coverage.
  Enabling them is part of this work.

### Fix `schemas` so it covers the schemas of the profile's tables

Found by the invariant suite, not yet fixed. `complete()` builds the `schemas`
list by calling `addSchema(table.getNormalizedSchemaName())` over the table
references — but `addSchema` returns early on a null schema, and the pass that
applies `withDefaultSchema(defaultSchema)` to those same tables runs **later in
the same method**. A statement whose tables were unqualified in the SQL, which
is the common case, therefore contributes nothing to `schemas` and then has its
tables filled in afterwards.

**Live impact: 1,816 of 3,608 profiles — 50% — list a table in a schema their
`schemas` list omits; most have an empty list entirely.** For example a profile
with `tables = ['search.users']` and `schemas = []`.

- The fix is probably to derive `schemas` *after* the default-schema pass, or
  to pass the default into `addSchema`. Check `SchemaAnalyzer`, which does its
  own `addSchema` loop earlier and has the same null-schema blind spot.
- Watch the ordering: `complete()` currently uses `schemas` to set
  `isMultiSchema` and to collapse `schemaName` to the single schema, so moving
  the derivation moves those too.
- A related invariant is disabled for the same root cause: a cross-schema
  statement with one qualified and one unqualified table reports
  `isMultiSchema = true` while listing a single schema. Not violated in the
  live corpus (0 of 3,608), because its cross-schema statements qualify both
  sides — but the fixture reproduces it.
- Both invariants are `@Disabled` in `SqlProfileInvariantsTest`; enabling them
  is the definition of done.

### Do another tech-debt pass, front end and back end, and work the findings into the plan

A fresh audit of both sides — and the output is not another list. The point is
to get the debt worth doing *scheduled*: promoted into WORK_TODO items or plan
docs, so it competes for time with everything else here instead of sitting in
an inventory nobody is committed to.

- **Start from `docs/TECH_DEBT.md`, and re-verify it.** It was pruned
  2026-08-15 to only what was true at that moment, so it is a good baseline
  rather than a wish list — but it is still a snapshot. Confirm each entry
  still holds, then add what has appeared since. What survived the prune, as
  the shape of what to check:
  - **Backend:** the unguarded nullable `RuntimeMetrics` dereferences in
    `SqlRepositoryService` (four sites, NPE on the rollup endpoints); the
    uncached `getAllAnalyzedSql()` re-merge on every request; the analyzer
    loop swallowing exceptions to a `debug` log; no `@ControllerAdvice`;
    triplicated group-by-digest merge logic; two graph stacks.
  - **Config:** `include-stacktrace: always` in the base profile;
    `open-in-view: true`; internal hostnames still in git history.
  - **Frontend:** 19 of 37 specs still Angular CLI stubs; no error UX on
    ~54 of ~60 subscribes; unmanaged subscriptions; the two god components;
    pinned-and-stale `core-js ^2`, `@types/node ^12`, `eslint ^7`,
    `@typescript-eslint ^4`, unbounded `highlight.js ">=9"`; committed
    legacy scaffolding — `.angular-cli.json`, `ngcc.config.js`,
    `protractor.conf.js`, `e2e/`; `legacy-peer-deps=true` masking conflicts.
- **Audit against current conventions, not the ones being migrated away
  from.** The front end now has a settled baseline worth measuring
  component-by-component: the global lens vocabulary in `styles.css`, the
  URL-state rule, `TagField` as the single tag control, the Ledger density
  modifier. A component still carrying its own copy of a global class, or
  filter state that never reaches the URL, is now a finding — before the
  migration it was just the house style. Same on the backend: measure against
  how the domain packages are actually organised today.
- **This is partly a meta-task.** `TECH_DEBT.md` stays the raw ledger — a
  point-in-time inventory that gets entries deleted as they are fixed. The
  work is deciding which entries are worth doing and writing *those* up here,
  where they can be sequenced. Obvious early candidates from the current
  ledger: the legacy-component retirement (already an item), caching
  `getAllAnalyzedSql()`, and the nullable-metrics null-guard — the last two
  share a root cause and a file, and the caching one is a prerequisite for
  honestly judging whether the query-engine refactor at the bottom of this
  list is needed at all.
- **Front end and back end can be separate passes.** They have different
  reviewers, different conventions and largely disjoint findings; splitting
  them into two items is fine and probably cleaner than one audit that has to
  hold both in its head.

### Show status, first/last seen, executed, impact and rows scanned in the table's query list

The queries list under the table view should carry these per query: **status,
first seen, last seen, executed, impact, rows scanned**.

- **What renders it today.** The **Queries card on `TableLensComponent`**, not
  a shared row component — `table-lens.component.html` has its own
  `.query-row` / `.query-row-head`, iterating `queries` (`QueryHit`, from
  `POST /queries/search`). Per row it currently shows: the query type as a
  link to `/analyzed-sql/{digest}`, `executed` ("N execs"), one time value, a
  full-scan badge, analyzer/analysis-failed badges, tags, and the SQL text.
  So of the six asked for, only **executed** is there today.
- **Every field is already on the payload** — the card loads full
  `SqlProfile`s, so this is presentation only, no backend or DTO change:
  - status → `profile.queryActivityStatus` (`ACTIVE` / `DORMANT` / `UNKNOWN`)
  - first seen → `profile.runtimeMetrics.firstSeen`
  - last seen → `profile.runtimeMetrics.lastSeen`
  - executed → `profile.runtimeMetrics.executed` *(already shown)*
  - impact → `profile.runtimeMetrics.totalTime` (picoseconds)
  - rows scanned → `profile.runtimeMetrics.rowsScanned`
    (`averageRowsScanned` also exists — decide which, per-execution average
    is usually the more comparable number)
  - `runtimeMetrics` is a nullable union; the card already guards it with
    `@if (hit.profile.runtimeMetrics; as m)` and must keep doing so.
- **The time it shows now is not impact.** `queryTime()` returns
  `averageTime / 1e9` rendered by the lens's own `fmtMs()`. "Impact" as the
  SQL view and the Ledger lists use it is **total** time. Decide whether
  impact replaces the average or sits beside it — showing both is defensible,
  showing one labelled ambiguously is not.
- **Reuse the existing formatting.** The lens does not use `app-pico-view` at
  all (it hand-rolls `fmtMs`); the Ledger pages, `app-sql-summary` and the SQL
  view all do. Durations should go through `app-pico-view`, counts through the
  `number` pipe for thousands separators, and status through the same
  active/dormant treatment `sql-summary` uses, rather than a third rendering
  of the same values.

Open questions:

- **`app-sql-summary` already renders exactly this set** — status, first seen,
  last seen, executed, impact, avg scanned — as a `.metric-row` list. The
  obvious move is for the lens to use it instead of growing its own row. But
  it is shared: `small-sql-view` renders it, and `tagged-sql-view`
  (`/analyzed-sql/by-tag/:tag`) renders that, plus the legacy `table-view`.
  Adopting it in the lens is free; *changing* it is not, so if the lens needs
  a different shape the component needs an input rather than an edit.
- **Row versus column layout.** The lens query list is a stacked card per
  query, not a table, so six more values need a place to sit — a metric strip
  in `.query-row-head`, or the `.metric-row` column `sql-summary` already
  uses. This is the actual design decision in the item.
- **Density.** The card lists many queries for a busy table; adding six values
  per row is a real cost to scanning. Worth checking against a table with
  hundreds of queries before committing to always-on.

### Make the column drill-down reuse the table lens with the column selected

`/table/{schema}/{table}/{column}` should render the same table component as
`/table/{schema}/{table}`, with that column selected so the queries list is
filtered by it — not a separate standalone view.

The mechanism already exists; it is the *route* that has not caught up.

- **What renders what today** (`ui/src/main.ts`):
  - `table/:schema/:name` → **`TableLensComponent`**
  - `table/:schema/:name/:column` → **`TableViewComponent`** (legacy)
  - `table/:schema/:name/queries` and
    `table/:schema/:name/join/:joinSchema/:joinTable` → also `TableViewComponent`
- **The lens already does column drill-down.** `TableLensComponent` has
  `drillColumn`, `isDrillable()` (join/criteria roles only — a select-only
  column cannot constrain a query, so it does not drill) and
  `drillIntoColumn()`, which writes `?columns=<schema>.<table>.<column>` via
  `patchUrl` and re-runs `runQuerySearch()`. It seeds from
  `state.columns[0]` on load. So the lens is already a column-filtered table
  view — reachable only by clicking a column *inside* the lens, never by URL
  path.
- **The queries card filters through the shared search vocabulary.** The
  `columns` param is `search-url.ts`'s csv, which becomes
  `QuerySearchRequest.columns` and delegates to `SqlProfile.constrainedBy` —
  "appears in a join or the criteria", the same meaning the legacy column
  view always had.
- **This is also the main way users still reach the legacy component.**
  `app-column-link` links to `/table/{schema}/{table}/{column}` verbatim
  (`column-link.component.html`), and it is rendered by the SQL view,
  `sql-summary`, and the legacy table view. Every column link in the app
  currently lands on `TableViewComponent`. Fixing this route is most of what
  makes "retire the last two legacy UI components" possible.

Open questions:

- **Route param vs query param.** The lens reads the selection from
  `?columns=`; the route offers it as a path segment. Simplest is probably to
  point the route at `TableLensComponent` and have it seed `drillColumn` from
  the `:column` param when present — but then two URLs express the same state.
  Decide whether the path form redirects to the `?columns=` form (one
  canonical shareable URL, consistent with the URL rule in CLAUDE.md), or is
  supported as a first-class alias.
- **The `:column` route is a catch-all shape.** `table/:schema/:name/:column`
  will match `table/x/y/queries` too — today that is avoided only because the
  more specific `queries` route is declared first. Any reshuffle has to keep
  that ordering, or the segment collision bites.
- **Does the lens need a new input?** It currently derives everything from
  query params and its own route params; a `:column` path segment is a new
  source for the same field.
- **Dead code this unlocks.** If the column route moves to the lens, and the
  `queries` and `join/**` routes are decided alongside it, `TableViewComponent`
  loses its last entry points — see the retirement item. `app-column-link`'s
  target needs updating either way.

### Evaluate the UI against the optimization workflows it supports

A two-part exercise. The point of the first part is to produce the yardstick
for the second — evaluating the UI against a list written after the fact is
how you end up grading it on what it already does well.

**1. Enumerate the SQL-optimization workflows sql-explorer actually supports
today.** Derive the list from the app, not from what a query tool is
generally expected to do. Some starting shapes — find the expensive queries,
spot full scans, judge whether a query has a usable index, compare normalized
patterns, trace a query to its tables and columns, curate with tags — but the
real list, and the ones missing from it, come out of walking the app.

**2. Use that list as the yardstick to evaluate the UI end to end.** Per
workflow, record the weaknesses, the pain-points, and the strengths. Strengths
matter as much as the gaps: they are what the rest should be measured against,
and what a redesign must not break.

- Ground it in the pages that exist now, and walk each one:
  - **`/queries`** — the workbench, and the filters that carry most of the
    diagnostic weight (activity, timing and selectivity ranges, full scan,
    temp-to-disk, rows sorted, errors, truncated, multi-schema, the
    analysis-failure sets, tags, date-math ranges).
  - **`/analyzed-sql/:id`** — cost above the fold, the relevant-indexes panel
    and its leading-column scoring, the merged-variants group.
  - **`/table/:schema/:name`** — the table lens: columns, indexes, DDL,
    foreign keys, the queries card and the per-column and per-join
    drill-downs.
  - **`/schemas`, `/tables`** — the Ledger lists and the header tag filter
    strip.
  - **`/tags`** — the manager and its what-carries-this-tag detail.
  - **`/graph`**, **`/explain`**, **`/query-utilities`** — the supporting
    surfaces, which may turn out to serve workflows the main pages do not.
- Evaluate the **current** UI. The lens migration, the Ledger list pages, and
  `TagField` all landed recently; anything written against the older
  Bootstrap pages, the removed reports pages, or the standalone tag index is
  describing an app that no longer exists.
- Worth noting per workflow whether it is *reachable* — several capabilities
  exist but are only obvious if you already know they are there (the
  drill-downs, the by-tag views, the canned report endpoints with no page).
- The output is a document, not a patch. Fixes it turns up become their own
  items here.

### Capture example queries with their complete parameters

Analyzed profiles carry normalized SQL with the literals stripped — which is
what makes merging work, but leaves nothing you can actually run. Capture real
examples, the query text with its complete parameters, stored as a list
associated with the normalized digest they belong to.

- **Primary key: a time-based UUIDv7.** Not a ULID: there is no ULID anywhere
  in the project — no `ulid` reference in `src/`, `models/`, `shared-tags/`,
  `ui/`, `mcp/`, or the gradle build — and no `java.util.UUID` use either.
  Every existing entity (`Tag`, `Note`, `User`, `SqlSourceEntity`) is
  `@GeneratedValue(strategy = GenerationType.IDENTITY)` auto-increment, so
  either option is new here; UUIDv7 follows the rule "ULID only if already in
  use".
- **Fields:**
  - the query text, with its complete/actual parameters;
  - the normalized digest it is associated with;
  - an optional description of the query source;
  - the environment id;
  - the id of the user who created it.
- **Retrieval API:** example queries returned in **DESC order by id** — the key
  is time-based, so that is most-recently-added first — with pagination and
  limits supported.

Open questions, all of which want deciding before the first line of code:

- **This needs a new table and a new migration.** No digest is stored in the
  Explorer database today; the only one that reaches it is embedded in a tag
  `path` string (`/query/{digest}`, see `commentary/PathService`). Add a
  changeset to `src/main/resources/db/migration/db.changelog-master.xml` —
  the single master changelog every other table lives in.
- **Entity pattern to follow:** `commentary/Tag` and `commentary/Note` are the
  closest — JPA entity, `created_on timestamp not null default
  current_timestamp`, and an author FK to `users(id)` with
  `on update cascade on delete restrict`.
- **A UUIDv7 PK would be the first non-`IDENTITY` id in the schema.** Decide
  the column type (`binary(16)` vs `char(36)`) and where the value is
  generated: MySQL's built-in `UUID()` is v1, and `java.util.UUID` has no v7
  factory, so this is application-side and probably a new dependency.
- **Digest column shape.** `normalizedDigest` is a sha256 hex string — 64
  chars, from `Hashing.sha256()` in `analysis/parser/NormalizedQueryDigest` —
  so `char(64)`, indexed, since every read is "the examples for this digest".
- **"Environment id" needs defining.** Environments are config-keyed by *name*
  (`environments.<name>` in `application.yml`, selected at request time by the
  `X-Sql-Explorer-Environment` header); there is no numeric environment id.
  `SourceRefEntity` already stores `environment_name varchar(255)`, which is
  the precedent to match unless environments become real rows.
- **"User id" is nominal until auth exists.** There is a `users` table and an
  author-FK pattern, but auth is unimplemented (`auth/TODO.md`) and everything
  is attributed to the seeded `admin` user (id 1). Same default here.
- **Where does the example text come from?** Performance Schema yields
  normalized SQL only, so examples cannot be harvested from the existing
  import — they arrive by hand (paste in the UI, or a write endpoint) or from
  a source that carries full statements. This decides whether the work is an
  API, a UI affordance, or both.

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

### Resolve CTE columns through to their source columns

CTE queries are analyzed now — the bodies are walked, real tables and their
criteria and join columns are extracted, and the CTE name is kept out of both
the table list and the column references. What is still lost is the other
half: a reference *through* a CTE.

In the corpus's recursive example, `tree` is built from `node_link` and
then self-joined —

    JOIN `tree` `parent` ON `child`.`parent_object` = `parent`.`object_id`

— which is really `node_link.parent_object` joined to
`node_link.object_id`. Today that pair is dropped, because one side
resolves to the CTE rather than to a table. Mapping it through would recover
a genuine self-join relationship, and would make the graph and the table
lens's join panels reflect what the query actually does.

- The mapping is positional: a CTE may declare an explicit column list
  (`WITH tree(entity_id, parent_id, …) AS …`), in which case position *i* of
  the projection is that name, or it may inherit names from the body's select
  list.
- Recursion and `UNION` are what make it more than a lookup — the arms have
  to agree, and the recursive arm references the CTE being defined.
- A CTE selecting an expression rather than a column has no source column to
  map to, so the result is partial by nature; dropping those is fine, but the
  code has to expect them.
- Start at `analysis/WithItems.java`, which already collects the CTE names and
  bodies the mapping would need.

Related, from the same feature: the SELECT filter now also matches `WITH`
(a deliberate hack, since first-token typing cannot tell a CTE-wrapped SELECT
from a CTE-wrapped DML). All three CTEs in the local corpus wrap SELECTs, so
the false-positive rate here is zero — worth re-checking against a larger
corpus before assuming that holds.

### Clean all history so queries look anonymous

Scrub older git history

### Improve the way we manage environments in sql-explorer

Brainstorm on this, do we attach them to a user session? Does that require that we add user auth first? Do we add a 
higher level concept to link environments in a dev-test-prod way?

### Plan a Grafana + Prometheus metrics integration built on links and link templates

Plan it, don't build it. The shape: links and link templates that take you
from a thing in sql-explorer to the metrics for that thing in Grafana.

- **Link targets:** tables, schemas, and performance_schema digests.
- **Multiple digests via regex.** One link or template should be able to
  target a *set* of digests by pattern, not just one — a normalized pattern
  usually covers several perf_schema digests, and the useful dashboard is the
  one showing all of them together.
- **Bundle Grafana dashboard JSON** for the SQL dashboards worth having, so
  the integration ships something usable rather than only the plumbing.

Grounding, so "links to" means something concrete here:

- **There is prior art, and it is the thing to replace.**
  `graph/SqlGraphService.java:34` already builds a Grafana deep link —
  `https://grafana.local/d/dvUfZxpZk/sql-analysis?…&var-digest=%s&var-instance=All&from=now-30d&to=now`
  — hardcoded, single-digest, and carrying its own
  `// todo refactor into a grafana integration plugin`. Note it lives on the
  **v1** graph service, which TECH_DEBT lists as candidate dead API surface,
  so this work may inherit the idea rather than the code.
- **Which digest matters.** That link uses `perfDigests.getFirst()` — the
  *performance_schema* digest, not the normalized one. sql-explorer's own
  `normalizedDigest` is a sha256 hex of the normalized SQL
  (`analysis/parser/NormalizedQueryDigest`) and is an internal identity; the
  digests Grafana/Prometheus would be labelled by are MySQL's. A profile
  carries several of the latter, which is exactly why the regex case exists.
- **How tables and schemas are identified.** By name, and in the path shapes
  the tagging system already uses — `/schema/{s}`, `/schema/{s}/table/{t}`,
  `/query/{digest}` (`commentary/PathService`). Those paths are the natural
  key for attaching a link template to a kind of thing.
- **What a "link template" is:** a Grafana deep link with the variables left
  open — datasource, the `var-*` dashboard variables (digest / table /
  schema), and the time range — filled in from whatever the user is looking
  at. The regex case maps onto a Prometheus label matcher rather than a
  value: `digest=~"a|b|c"` (or a prefix/pattern), which is also how one
  Grafana variable can carry a set.

Open questions:

- **Do the metrics being linked to exist?** This integration assumes
  something exports SQL metrics labelled by digest / table / schema.
  sql-explorer does not. It has micrometer and a working scrape endpoint —
  `micrometer-registry-prometheus` + actuator, exposure `health,prometheus`,
  live at `/api/sql-explorer/actuator/prometheus` (note the
  `/api/sql-explorer` context path) — but the only custom meters are
  import-pipeline instrumentation (`performance_schema_process_row`,
  `@Timed("performance_schema_import")`). Nothing is labelled by digest,
  table or schema. So either an external exporter supplies them (a
  mysqld/perf-schema exporter is the obvious candidate) and this integration
  just links to it, or producing those series is the real first task. Settle
  this before anything else — it decides whether the item is a linking
  feature or an exporter.
- **Where does the bundled dashboard JSON live?** Nothing in the repo ships
  Grafana assets today. Needs a home and a story for how it gets into a
  Grafana (provisioning directory, import instructions, or the compose stack).
- **Are link templates stored or generated?** Configured per environment and
  persisted like a setting, or derived on the fly from the thing being
  viewed? The hardcoded constant is neither, which is why it only ever worked
  for one deployment.

### Filter queries in SQL or Parquet (DuckDB?) instead of in memory

Move query filtering out of in-memory processing and into a query engine —
predicates pushed into SQL, or evaluated over Parquet by something like
DuckDB.

- **Where the filtering happens today.** `QuerySearchService.search()` does
  one pass of `sqlRepositoryService.getAllAnalyzedSql().forEach(...)`,
  applying `matchesQuery` / `matchesNonScope` / `matchesScope` as Java
  predicates per profile, accumulating the table and schema facet counts as
  it goes, then `matches.sort(comparator(...))` over the whole match list and
  slicing offset/limit at the end. Filtering, faceting, sorting and pagination
  are all in heap, after materializing every profile.
- **And the scan is preceded by a rebuild.** `getAllAnalyzedSql()` is not
  cached — `CompositeSqlRepository` re-streams every source, regroups by
  digest, re-merges duplicates and re-sorts on *every call* (the standing
  TECH_DEBT backend-perf item). So each search pays a full merge before it
  filters anything. That item and this one share a root cause; whichever is
  done first should be done with the other in mind.
- **What the data actually is.** Profiles are Avro `SqlProfile` records held
  in heap per environment by `SqlRepositoryManager` — there is no relational
  store to push predicates into, so "filter in SQL" is not a matter of
  writing a WHERE clause against MySQL. The Explorer database holds tags,
  notes, users and sources; it does not hold profiles.
- **Parquet already exists, which makes the DuckDB route concrete.**
  `ParquetSink` writes the corpus with `AvroParquetWriter` to
  `parquet-sink.filename` (`/parquet/sql-repository.parquet` in the local
  stack) and `ParquetSqlRepository implements SqlRepository` reads it back —
  today as an unindexed full scan. A columnar engine pointed at that file is
  the least-new-machinery version of this.
- **The predicate surface that has to survive.** All of `QuerySearchRequest`:
  `queryRegex`; the ranges `avgTimeMs`, `maxTimeMs`, `avgRowsScanned`,
  `selectivity`, `rowsSorted`; `activity`; `types`; the `firstSeen` /
  `lastSeen` date ranges; `tags` + `tagMode` + `untaggedOnly`; `schemas` /
  `excludeSchemas` / `tables` + `tableMode` / `excludeTables`; `columns`
  (which delegates to `SqlProfile.constrainedBy` — join-or-criteria, not a
  bare select); the flags `fullScan`, `tmpTable`, `tmpDisk`, `errors`,
  `warnings`, `truncated`, `multiSchema`; `analyzerWarnings`,
  `analysisFailed`, `cleanAnalysis`, `analysisSkipped`, `failureKind`; and
  `sort` / `limit` / `offset`. Plus the facet counts, which are computed in
  the same pass and are not a filter.
  - The awkward ones are the analysis predicates and `columns`: they read
    nested/derived structure (`constrainedBy`, analyzer failure strings)
    rather than flat columns, so they need either a flattened projection in
    the Parquet schema or to stay in a post-filter step.
- **Tags live in a different store.** Tag filtering joins against the
  Explorer MySQL tags table (`queryTagsById()`), not the profile data — so
  any engine-side filter either federates the two or resolves tags to a
  digest set first and pushes that in.

Open questions:

- **Which of the two?** SQL against an existing store is not available
  (profiles are not in MySQL), so realistically this is "stand up a columnar
  layer over the Parquet we already write" — DuckDB, or Parquet predicate
  pushdown without a full engine. Worth naming that explicitly before anyone
  scopes it as the smaller-sounding option.
- **Where do the Parquet files come from and live?** Today one file per
  environment written at the end of a load. Per-environment, per-load and
  retention all need deciding if it becomes the query substrate rather than a
  sink.
- **Consistency with the in-memory results.** The current path merges by
  normalized digest before filtering; any engine-side implementation has to
  reproduce that merge, or read a projection that is already merged, or it
  will return different rows. Same for the sort comparator, which is
  `SqlProfileExtension.comparator` (activity, then metrics presence, then
  total time) rather than a plain ORDER BY.
- **Is it a problem worth solving yet?** State the scale honestly: the local
  corpus is ~3,500 analyzed queries across 294 tables and 25 schemas. A Java
  pass over 3,500 objects is not the bottleneck — the uncached per-request
  merge in front of it is the thing that actually costs, and that can be
  fixed on its own without an engine. The case for this item is headroom for
  much larger corpora, not present-day latency; if it is being scheduled for
  speed today, measure first.

### Don't fail analysis on a column with no candidate tables

`SELECT NOW` — the parentheses lost somewhere upstream, so jsqlparser reads
`NOW` as a bare column with no `FROM`. `SelectColumnAnalyzer` asks the
resolver which table it belongs to, there are no candidate tables, and
`ColumnTableResolver.selectTableNameFromCandidates` throws
`UnsupportedSqlException("missing schema")` — the only analyzer-thrown failure
in the whole corpus.

There is nothing to resolve. A column with no candidate tables cannot be
attributed to one, and asking is wasted work that turns into a spurious
`analysisFailed`. Returning "unresolved" and moving on is both cheaper and
more accurate than throwing.

- Start at `ColumnTableResolver.selectTableNameFromCandidates`, which throws
  when `findSchemaForTablesAndColumns` yields no schema.
- Check what the callers expect an unresolved column to look like — the
  analyzers already emit references whose table cannot be pinned down, so
  there may be an existing shape to reuse rather than a new one to invent.
- Worth confirming whether the lost `()` is a normalizer artifact or is
  already absent in the performance_schema text; if the former, the statement
  should arguably never have reached the analyzer looking like a bare column.

### Capture enough on an analysis failure to write a failing test from it

When an analyzer throws, capture the problematic SQL together with the
environment it was analyzed against — the tables, columns and whatever else
the analyzer reached for — so a failing test can be reconstructed directly
from a production failure instead of by hand.

- **Where failures are handled today.** `SqlAnalysisService.analyzeSql(String,
  SqlProfile.Builder, SqlSchemaRepository)` runs each analyzer in a
  `try { … } catch (Exception e)` and records only two things: a short string
  on the profile —

      builder.setAnalysisFailureReason("Failure on: " + analyzer.getClass().getSimpleName()
                                       + " message: " + getRootCauseMessage(e));

  — and `log.debug("Failure on: {}", …, e)`, which is off in normal operation.
  So a failure says *what* broke ("Failure on: SelectColumnAnalyzer message:
  with items list") and nothing about how to reproduce it. This is the
  TECH_DEBT "analyzer loop swallows all exceptions" item, approached from the
  reproducibility side rather than the observability one.
- **Everything needed is already in scope at the catch.** The method holds the
  original `sql` string and the `SqlSchemaRepository` the analyzers resolve
  against. Nothing new has to be plumbed through to capture it — it is
  discarded, not unavailable.
- **Target the harness that already exists.** `SchemaFixture.fromSql(ddl)` plus
  `.analyze(sql)` is how the analyzer tests are written. A captured failure
  should carry exactly what that pair needs — the SQL, and DDL for the tables
  and columns it referenced — so writing the regression test is a paste rather
  than an investigation.
- **The CTE work is the motivating example.** Three `WITH` queries failed with
  `Failure on: SelectColumnAnalyzer message: with items list`. Turning that
  into tests meant querying the API for the statements, reading them to work
  out which tables and columns they touched, and hand-writing a fixture to
  match. That reconstruction is the step this item removes; it is the same
  every time, and it is the reason failures sit unfixed.

Open questions:

- **Where does a captured failure go?** Structured log fields, a column on the
  profile, a table, or an admin view that renders a ready-to-paste fixture.
  The last is the most useful and the most work.
- **How much schema?** The full repository is large and may carry column names
  worth not copying around; the referenced tables and their columns are enough
  to reproduce and much smaller. Deriving "referenced" is itself analysis,
  which may have partly failed — falling back to the statement's table list is
  probably good enough.
- **Exception and stacktrace, or just the reconstruction inputs?** The
  stacktrace is what makes the *cause* obvious; the SQL and schema are what
  make the *test*. They have different audiences and possibly different
  retention.
- **Keep the existing string stable.** `"Failure on: "` is load-bearing —
  `QuerySearchService.ANALYZER_FAILURE_PREFIX` and two `SqlProfileReportService`
  reports classify analyzer failures by that prefix. Anything richer has to be
  additive rather than a reformat.
