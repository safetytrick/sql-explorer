# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

SQL Explorer is a full-stack tool for analyzing MySQL performance. It reads queries from MySQL's Performance Schema (or CSV/Parquet files), parses and normalizes them, and surfaces insights about schema usage, query patterns, and performance impact ("Pain" metrics).

- **Backend**: Java 21 + Spring Boot 4.0.6 (Gradle)
- **Frontend**: Angular 21 (standalone components) + Material Design (`ui/`)
- **Data models**: Apache Avro (`models/` subproject)
- **Database**: MySQL 8.4.4, Liquibase migrations

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
ng test                     # Run Karma/Jasmine unit tests
npm run e2e                 # Run Playwright e2e tests
```

### Docker (local dev)

Start services in order — MySQL must be up before the backend:

```bash
docker compose up -d explorer-mysql
docker compose up -d sql-explorer
docker compose up -d explorer-ui
docker compose up explorer-shared-tags   # Optional: load table/SQL tags
```

UI is accessible at `http://localhost:4200`. Backend runs on port 8080.

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
| `/table-graph/schema/:schema` (+ variants) | TableGraphLoaderComponent → TableGraphComponent |

Note: the `*-lens` components (`TableLensComponent`, `GraphLensComponent`, `SchemaTreeComponent`) are the current UI; the older `TableViewComponent` / `TableGraph*` / `SchemaListComponent` are legacy and still routed (see `docs/TECH_DEBT.md`).

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

### Data Flow

1. SQL loaded from Performance Schema or CSV/Parquet files
2. `QueryNormalizer` deduplicates similar queries
3. `SqlAnalyzer` extracts tables/columns/join patterns
4. Results stored in the Explorer MySQL database via JPA
5. Frontend fetches from REST endpoints and renders profile/graph views
6. Users tag and annotate queries; tags are loaded via the `shared-tags` subproject

### Subprojects

- **`models/`** — Avro schema definitions, compiled to Java via the Avro Gradle plugin
- **`shared-tags/`** — Liquibase migrations for the tagging schema

### Configuration

- `src/main/resources/application.yml` — Spring config; backend listens on port 8080
- `src/main/docker-local/local.env` — Docker env vars (`ENVIRONMENT_PROFILE=override`, JVM flags)
- `src/main/docker-local/config/` — TSV files for statement analysis classification
