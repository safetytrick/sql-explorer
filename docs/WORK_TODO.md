
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

### Clean all history so queries look anonymous

Scrub older git history

### Improve the way we manage environments in sql-explorer

Brainstorm on this, do we attach them to a user session? Does that require that we add user auth first? Do we add a 
higher level concept to link environments in a dev-test-prod way?
