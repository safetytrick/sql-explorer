# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

SQL Explorer is a full-stack tool for analyzing MySQL performance. It reads queries from MySQL's Performance Schema (or CSV/Parquet files), parses and normalizes them, and surfaces insights about schema usage, query patterns, and performance impact ("Pain" metrics).

- **Backend**: Java 21 + Spring Boot 4.0.6 (Gradle)
- **Frontend**: Angular 21 (standalone components), no UI framework (`ui/`) — Material, Bootstrap, jQuery and Popper have all been removed; styling is component-scoped SCSS over the design tokens in `ui/src/styles.css`
- **Data models**: Apache Avro (`models/` subproject)
- **Database**: MySQL 8.4.4, Liquibase migrations

## Keep deployment specifics out of the repo: use neutral names

sql-explorer gets pointed at real databases, so real names turn up while
debugging: schemas, tables, hosts, digests, accounts. **None of them belong in
anything committed** — code, tests, fixtures, docs, comments, commit messages
or bead text that gets exported. That covers:

- organisation and product names, and schema or table names from a real
  deployment;
- hostnames, endpoints, IP addresses, cloud account ids, profile and role names;
- MySQL usernames and people's names or handles;
- digests, query text or row counts copied from a real Performance Schema;
- links to internal wikis, tickets or dashboards.

Use neutral stand-ins instead: `app_*` schemas, generic tables (`account`,
`site`, `account_site`), `` `reader`@`10.0.0.1` ``, `db.example.com`, and
synthetic digests. When reproducing a bug found against a real database,
rebuild the *shape* of the statement on a made-up schema (`SchemaFixture.fromSql`)
rather than pasting it — the shape is what the test needs anyway.

Configuration that has to name a real deployment stays local: `scripts/` is
git-ignored for that reason. Before committing, scan the diff for anything
you only know because of a real system.

## Commands

### Backend

```bash
./gradlew assemble          # Build the Java application
./gradlew test              # Run all JUnit 5 tests
./gradlew test --tests com.github.safetytrick.sqlexplorer.SomeTest  # Run a single test
```

### Frontend (`ui/`)

```bash
npm install                 # Install dependencies
npm start                   # Dev server at http://localhost:4200
npm run build               # Production build
npm run lint                # Run eslint (eslint src --ext .ts)
npm run test:ci             # Karma/Jasmine unit tests, headless, single run
ng test                     # Same, in watch mode
```

### The gate before pushing

There is no pre-push hook. The only hook is `pre-commit`, and it guards
`master`/`dev` — this repo's branch is `main`, so it never fires. The real
gate is `.github/workflows/ci.yml`, which will run these on the push whether
or not you did. Run them first:

```bash
./gradlew build
```

```bash
cd ui && npm run lint && npx tsc --noEmit -p src/tsconfig.app.json && npm run test:ci && npm run build && npm run e2e
```

`npm run e2e` is the Playwright smoke suite. It serves the production build
you just made and stubs every backend call at the network boundary, so it needs
no MySQL, no Spring Boot and no docker — which is what lets it live in the
frontend CI job instead of a job with service containers. That shape is
deliberate: the suite it replaces needed the whole stack, so CI never ran it and
it rotted (sqlx-6jy). It must be run after `npm run build`, because it serves
`ui/build`. First run on a machine needs `npx playwright install chromium`.

Note `./gradlew build`, not `./gradlew test` — CI runs the former, and it
covers checks the latter does not. The standalone `tsc --noEmit` also catches
things `ng build` does not, and must be run from `ui/` (the project path is
relative).

### Capturing profiles

A source is read at startup, on a schedule if its environment declares one, and
on demand:

```bash
curl -X POST localhost:18180/api/sql-explorer/environments/regression/pull
```

`pullCron` on an environment (a Spring cron expression) schedules it; absent, it
is not scheduled, and nothing is scheduled by default. `sql-explorer.prune-
redundant-captures` deletes captures another capture already contains — off by
default, and when off it logs what it would remove.

The pruning rule is structural, not count- or age-based, and the difference
matters: within a run of captures the newest contains the others, but across a
Performance Schema reset it contains nothing of them. The capture before a reset
is the only record of which queries ran in that period, so it is never pruned —
"keep the last N" would eventually delete exactly that one (sqlx-ijz).

### Docker (local dev)

Start services in order — MySQL must be up before the backend:

```bash
docker compose up -d explorer-mysql
docker compose up -d sql-explorer
docker compose up -d explorer-ui
docker compose up explorer-shared-tags   # Optional: load table/SQL tags
```

UI is accessible at `http://localhost:4200`. Backend runs on port 8080.

#### Running from local builds

`docker compose build` intermittently fails to reach the registry through
Docker Desktop's proxy (`sqlx-dec`) — on `node:22` for the UI, and on the
backend before it can even pull the dockerfile frontend. Both Dockerfiles are
two-stage and only their *builder* stages pull anything; the runtime stages
just place one artifact. So build on the host and mount over that path:

```bash
./gradlew assemble && (cd ui && npm run build)
```

```bash
docker compose -f docker-compose.yml -f docker-compose.local-build.yml up -d
```

Re-run the same `up -d` after every rebuild, and add `--force-recreate` for the
UI:

```bash
docker compose -f docker-compose.yml -f docker-compose.local-build.yml up -d --force-recreate explorer-ui
```

**A hard reload is not enough, and neither is `restart`.** `ng build` deletes
and recreates its output directory, so the bind mount is left holding a dead
inode and nginx serves the previous bundle indefinitely — `restart` reuses the
same mount, so only recreating the container re-resolves it. The jar is a
single file that is overwritten in place, so the backend only needs a
`restart`.

**Rebuilding the jar while the backend is running breaks the running process,
and destroys that run's capture.** The JVM loads classes from the fat jar
lazily, so overwriting it under a live container leaves the process unable to
load anything it had not already touched. Two symptoms, and the second is the
expensive one:

- Requests fail with `NoClassDefFoundError` on some unrelated library class —
  observed as a 500 from `/analyzed-sql/{id}` naming
  `com/fasterxml/jackson/databind/...`, which reads exactly like a dependency
  problem and is not one.
- **Shutdown aborts before `@PreDestroy` runs.** Observed:
  `ClassNotFoundException: org.apache.catalina.Lifecycle$SingleUse` thrown out
  of `SpringApplicationShutdownHook`, 0.46s into a stop with a 60s grace
  period. Anything relying on an orderly shutdown simply does not happen.

So "the backend only needs a restart" means *restart before you exercise it*:
any `./gradlew build` or `assemble` during the container's life has already
broken that run, including one you ran only to gate the frontend.

Captures used to be the casualty of the second point — `ParquetSink` held its
writer until `@PreDestroy`, and Parquet buffers the row group until close, so a
shutdown that never ran left a 4-byte file holding nothing. Nine such stubs
accumulated before this was traced. `sqlx-fx2` changed the sink to open, write
and close per import, so a capture is sealed the moment its import finishes and
survives however the process ends. Stubs in `src/main/docker-local/parquet/`
predate that change.

This trap looks exactly like a code bug, so when behaviour disagrees with the
source, compare the served bundle against the local one before believing
either:

```bash
curl -s localhost:4200/ | grep -o 'main-[A-Z0-9]*\.js'; ls ui/build/sql-explorer-ui/browser/main*.js
```

Deliberately **not** named `docker-compose.override.yml`: that name applies
automatically, so it would shadow a genuine rebuild without saying so, and on a
fresh clone — where `build/libs` and `ui/build` do not exist — Docker would
create empty directories, nginx would serve 404s and the JVM would find no jar,
with nothing to explain why.

The failure this guards against is not the slow rebuild. It is that a stale
container makes the running app lag the code *silently*: a reported bug once
turned out to be a guard that simply was not in the bundle being run.

### Git remotes

`origin` is GitHub (`git@github.com-mine:safetytrick/sql-explorer.git`) and is
what `main` tracks, so a bare `git push` goes to GitHub and `git status`
ahead/behind is accurate. There is also a `work` remote pointing at GitLab
(`git@gitlab.com:michael.nielson.go/sql-explorer.git`); it fetches into
`refs/remotes/work/*` and is not involved unless you name it.

These were cross-wired until 2026-08-16 — `origin` fetched into
`refs/remotes/github/*`, `work` fetched into `refs/remotes/origin/*`, and
`branch.main.remote` named a `github` remote that did not exist — which meant
a bare push failed and `origin/main` tracked GitLab, so every ahead/behind
reading was wrong. The refspecs and the branch config are normalized now.

## Architecture

### Backend Modules (`src/main/java/com/github/safetytrick/sqlexplorer/`)

The backend is organized by domain, not by layer. Code that changes together lives together.

- **`analysis/`** — Core SQL parsing pipeline. `SqlParser` (in `analysis/parser/`) tokenizes SQL via jsqlparser; `QueryNormalizer` merges similar patterns; the `SqlAnalyzer` implementations (`TableNameAnalyzer`, `SchemaAnalyzer`, `JoinColumnAnalyzer`, …) extract tables, columns, joins, aliases; `SqlAnalysisService` orchestrates the full flow. The `resolver/` subpackage handles column-to-table resolution including alias expansion.

- **`schema/`** — Introspects the connected MySQL databases for tables, columns, indexes, and foreign keys. `SchemaResource` is the REST endpoint (`/metadata/table/**`).

- **`evidence/`** — Wraps Performance Schema execution statistics and metrics (exec counts, latency, etc.).

- **`profiles/`** — Aggregates analysis + evidence + tags into per-table/per-query profiles. `SqlProfileResource` is the primary REST API consumed by the frontend.

- **`graph/`** — Builds table relationship graphs (join relationships, "queried with" co-occurrence). `SqlGraphResource` serves graph data.

- **`commentary/`** — User tagging (`TagsResource`) and notes (`NotesResource`) for queries and tables.

- **`model/`** — Shared domain objects.

- **`spring/`** — Spring configuration classes.

### Frontend (`ui/src/app/`)

Components map directly to the URL hierarchy:

| URL pattern                          | Component                                       |
|--------------------------------------|-------------------------------------------------|
| `/queries`                           | QueriesWorkbenchComponent                       |
| `/graph`, `/graph/:schema`           | GraphLensComponent                              |
| `/schemas`                           | SchemaListComponent                             |
| `/tables`, `/tables/:schema`         | TableNamesComponent                             |
| `/table/:schema/:name`               | TableLensComponent                              |
| `/table/:schema/:name/queries` (+ `/join/**`, `/:column`) | TableViewComponent         |
| `/analyzed-sql/:id`                  | SqlViewComponent                                |
| `/tags`                              | TagManagerComponent (master/detail)             |
| `/table-graph/schema/:schema` (+ variants) | TableGraphLoaderComponent → TableGraphComponent |

Note: the `*-lens` components (`TableLensComponent`, `GraphLensComponent`, `SchemaTreeComponent`) are the current UI. `TableViewComponent` and `TableGraph*` are the last legacy components still routed — both have lens replacements, so what remains is a deletion decision, not a restyle (see `docs/plans/legacy-ui.md`). `SchemaListComponent` / `TableNamesComponent` are no longer legacy: they were rebuilt on the Ledger.

An unknown URL hits the `**` catch-all and renders `NotFoundComponent`: a "Not found" heading, the address that was attempted, and links back to Tables, Schemas and Queries. The address bar keeps the bad path, which is right — it is what the page shows you.

This paragraph previously said there was no catch-all and that a bad URL rendered an empty outlet while silently resetting the address bar. Both halves were wrong, found by the e2e suite on 2026-08-31 (sqlx-6jy) while trying to pin the behaviour the doc described. The route is pinned by a test now, so the doc and the app can no longer drift apart quietly.

Key services: `AnalyzedSqlService` / `SchemaLensService` (query profiles & schema data), `QuerySearchService` (the queries workbench), `SourceService` (data sources), `EnvironmentService` (active environment). Shared response types live in `ui/src/app/api-types.ts`.

#### Every view is a link: state belongs in the URL

**Any filtering or view state must be reflected in the URL, so that every view
can be shared as a link.** If a colleague can see it, they must be able to send
it. A filter that lives only in component state is a bug, not a shortcut.

In practice:

- Write state back with `router.navigate([], { queryParams, replaceUrl: true })`
  so filtering doesn't spam browser history, and seed the component from
  `route.snapshot.queryParamMap` on init.
- Encode and decode through a shared pure codec rather than ad-hoc reads —
  `search-url.ts` is the vocabulary for anything search-shaped (the workbench
  and `/graph?source=search` speak it identically), and `graph-url.ts` covers
  page-owned *presentation* params, which are prefixed `var-` so they can't
  collide with cross-cutting ones.
- Omit defaults so an untouched view keeps a clean URL.
- Share intent, not resolved results: a tag filter travels as the tag name and
  is re-resolved against live data when the link opens, the same way date-math
  expressions like `now-7d` stay relative. A snapshot of what the tag matched
  at share time would go stale.
- Reflect *derived* view state too — an open drill-down, a selected row, an
  expanded panel — not just the text in filter boxes.

Known gap: the `/tables` all/tables/views type filter is *not* URL-backed. It
predates the rule and now sits in the same control strip as `?tags=`, which
makes the asymmetry conspicuous — you can share "tagged clickhouse" but not
"views only". Worth closing.

#### The lens vocabulary is global — use it, don't re-transcribe it

`ui/src/styles.css` owns the shared classes. Every lens page used to carry its
own copy — five stylesheets each defined `.tag-chip`, four defined
`.data-table` — which is how they drifted apart. The vocabulary now lives once:

`.view-header` (+ `.view-title`, `.view-sub`, `.view-actions`), `.lens-card`,
`.card-head` (+ `.card-head-title`, `.card-head-note`), `.card-empty`,
`.table-scroll`, `.data-table` (+ `.num`, `.ctr`), `.tag-chip`, `.tri-btn`,
`.btn`, `.ref-pill`, and the density modifier `.data-table.ledger` with
`.ledger-scroll` and `.dur-bar`.

A new page should reach for these first. Component SCSS is for what is
genuinely local — column widths, page-specific controls.

Two things to know before touching them:

- **Component-scoped copies still win** on specificity (an attribute selector
  outranks a bare class), so a global is a baseline, not an override. But any
  property the global sets that a local copy does *not* set will newly apply
  there. Changing a global rule reaches every page; screenshot-diff the pages
  that keep their own copy before assuming it doesn't.
- **`position: sticky` needs a scrolling ancestor.** `.lens-card`
  (`overflow: hidden`) and `.table-scroll` (`overflow-x`) both create scroll
  containers that never scroll vertically, so a sticky header inside them
  silently scrolls away with the rows. `.ledger-scroll` gives the table a
  bounded region to pin against; that is why it exists.

#### Tagging: one component, `TagField`

`app-tag-field` (`ui/src/app/tag-field/`) is the only editable tag control —
chips with a hover `×`, and a `+` that summons a typeahead popover for
attaching an existing tag or creating a new one. `dense` mode is for table
rows, where the `+` stays a faint ghost until the row is hovered. Its
predecessor `app-tag-view` rendered a persistent text input in every instance
and is deleted; don't reintroduce that shape — an untagged row should cost one
dim glyph, not a full-width underline.

Two correctness details that are easy to undo by accident:

- Candidate names come from `TagColorsService.getSummaries()`, which is
  `shareReplay(1)`. A 300-row table therefore issues **one** request and
  filters client-side. Per-keystroke server autocomplete from every mounted
  row is what this replaced.
- **Subscribing to that observable is also what populates the chip colour
  map.** The service fills a `Map` inside a `tap`; with no subscriber every
  chip renders in the fallback grey and never updates, because mutating a Map
  triggers no change detection.

Tag paths are `/schema/{s}`, `/schema/{s}/table/{t}`, `/query/{digest}`.
`DELETE /tag/:id` detaches by *association* id, not tag id — and a row
carrying two tags returns both, so match by name rather than taking the first.
`SqlProfile` carries no `tags` field at all; a query's association has to be
resolved via `POST /tags/path`, which batches and returns a map keyed by
exactly the paths sent.

The list pages carry a tag filter strip in the header (`tag-filter/`), sharing
the workbench's URL vocabulary: `?tags=a,b` plus `tagMode=ALL|ANY`. It
defaults to ALL and writes `tagMode` whenever tags are selected, deliberately
against the omit-defaults rule — the workbench defaults to ANY, so a bare
`?tags=a,b` would otherwise mean AND in one place and OR in the other.

### Data Flow

1. SQL loaded from Performance Schema or CSV/Parquet files
2. `QueryNormalizer` deduplicates similar queries
3. `SqlAnalyzer` extracts tables/columns/join patterns
4. Results stored in the Explorer MySQL database via JPA
5. Frontend fetches from REST endpoints and renders profile/graph views
6. Users tag and annotate queries; tags are loaded via the `shared-tags` subproject

**Analysis is reused across loads, so bump `AnalyzerVersion.ANALYZERS` when
you change what analysis produces.** A load reuses an earlier profile's
analysis for the same MySQL digest and default schema without parsing again,
taking it from the resident corpus or the latest capture (`AnalysisReuse`,
sqlx-sqy.5). Reuse is keyed on
`AnalyzerVersion.CURRENT`, which folds in `QueryNormalizer.VERSION`. Any change
to parsing, the analyzers, table extraction or a profile's derived fields that
would make the same statement analyse differently must bump `ANALYZERS`.
Forgetting fails silently: the old analysis just keeps being served.

### Subprojects

- **`models/`** — Avro schema definitions, compiled to Java via the Avro Gradle plugin
- **`shared-tags/`** — Liquibase migrations for the tagging schema

### Configuration

- `src/main/resources/application.yml` — Spring config; backend listens on port 8080
- `src/main/docker-local/local.env` — Docker env vars (`ENVIRONMENT_PROFILE=override`, JVM flags)
- `src/main/docker-local/config/` — TSV files for statement analysis classification


<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:6cd5cc61 -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/SYNC_CONCEPTS.md for details and anti-patterns.

## Agent Context Profiles

The managed Beads block is task-tracking guidance, not permission to override repository, user, or orchestrator instructions.

- **Conservative (default)**: Use `bd` for task tracking. Do not run git commits, git pushes, or Dolt remote sync unless explicitly asked. At handoff, report changed files, validation, and suggested next commands.
- **Minimal**: Keep tool instruction files as pointers to `bd prime`; use the same conservative git policy unless active instructions say otherwise.
- **Team-maintainer**: Only when the repository explicitly opts in, agents may close beads, run quality gates, commit, and push as part of session close. A current "do not commit" or "do not push" instruction still wins.

## Session Completion

This protocol applies when ending a Beads implementation workflow. It is subordinate to explicit user, repository, and orchestrator instructions.

1. **File issues for remaining work** - Create beads for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **Handle git/sync by active profile**:
   ```bash
   # Conservative/minimal/default: report status and proposed commands; wait for approval.
   git status

   # Team-maintainer opt-in only, unless current instructions forbid it:
   git pull --rebase
   git push
   git status
   ```
5. **Hand off** - Summarize changes, validation, issue status, and any blocked sync/commit/push step

**Critical rules:**
- Explicit user or orchestrator instructions override this Beads block.
- Do not commit or push without clear authority from the active profile or the current user request.
- If a required sync or push is blocked, stop and report the exact command and error.
<!-- END BEADS INTEGRATION -->
