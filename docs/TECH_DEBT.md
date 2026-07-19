# SQL Explorer — Technical Debt Report

_Snapshot: 2026-07-19. Covers the Java backend (`src/`), the Angular frontend (`ui/`),
and build/CI/infra/config/docs. Findings are grouped by area and ranked by severity
(HIGH / MED / LOW). File paths and line numbers are as of this snapshot and will drift._

## How to read this

This is a point-in-time inventory, not a work order. Severity reflects blast radius ×
likelihood, not effort. The **Top priorities** table is the recommended order of attack;
everything else is detail to pull from when scheduling the work.

A few items were fixed while this report was being compiled — see
[Recently addressed](#recently-addressed) so the list isn't re-litigated.

---

## Top priorities (cross-cutting)

| # | Finding | Area | Severity |
|---|---------|------|----------|
| 1 | **No CI/CD at all** — nothing runs tests, lint, spotbugs, or dependency-check on push/PR | Build | HIGH |
| 2 | **`getAllAnalyzedSql()` rebuilds a full group-by + merge + sort on every request** — hit by search, schema-tree, graph, and 9 report methods | Backend | HIGH |
| 3 | **Unguarded nullable `RuntimeMetrics` → NPE on the `/table`, `/tables`, `/schemas` rollup endpoints** | Backend | HIGH |
| 4 | **Error responses leak stack traces in every environment** (`include-stacktrace: always` in base config) | Config | HIGH |
| 5 | **Internal infra hostnames committed to git** (staging RDS + bastion in tracked files/history) | Infra | HIGH |
| 6 | **Effectively zero automated test coverage** — 28 UI specs are CLI stubs; 0 backend integration/web tests | Both | HIGH |
| 7 | **Old-vs-new duplication** — legacy `table-view`/`table-graph`/`schema-list`/`sql-view` coexist with `*-lens` replacements, both routed | Both | MED-HIGH |
| 8 | **Analyzer loop swallows all exceptions** to a debug log — analyzer bugs silently degrade data quality | Backend | HIGH |

---

## 1. Backend (Java)

### Error handling

- **HIGH — Analyzer loop swallows all exceptions.** `analysis/SqlAnalysisService.java:47-54` runs
  every analyzer in `try { … } catch (Exception e)`, recording failure only as a `"Failure on: …"`
  string logged at `debug`. Any analyzer bug (NPE, CCE, logic error) becomes an invisible
  "analysis failed" for that query. It's load-bearing too: `QuerySearchService` keys a UI filter off
  that string prefix (`ANALYZER_FAILURE_PREFIX`).
- **MED — Empty catch.** `evidence/fileloader/PerformanceSchemaFileSourceFactory.java:109`:
  `catch (IllegalArgumentException ignore) { }` — no logging.
- **MED — `SQLException → RuntimeException` with no context, repeated 6×** across the schema loaders
  (`schema/ForeignKeyLoadingService.java:61`, `IndexLoadingService.java:119`,
  `ColumnEncodingService.java:81`, `CreateStatementService.java:47`, `TableColumnService.java:174`,
  `SchemaRepositoryFactory.java:79` — the last throws `IllegalArgumentException`, inconsistent). No
  shared handling; with no `@ControllerAdvice` these bubble to a bare 500.
- **MED — Broad `catch (Exception)`** in the import/reformat path (`evidence/SqlEvidenceInputService.java:135, 292`).
  _Good pattern to preserve:_ `IndexLoadingService.java:163-166` and `ForeignKeyLoadingService.java:171-174`
  close-then-rethrow with an explanatory comment.

### Performance / in-memory

- **HIGH — `CompositeSqlRepository.getAllAnalyzedSql()` rebuilds everything per call**
  (`evidence/CompositeSqlRepository.java:52-110`): streams all profiles, groups by digest, re-merges
  duplicates, re-sorts — nothing cached. Called per-request by `QuerySearchService`,
  `SchemaTreeService`, `GraphV2Service`, and **9 methods** in `SqlProfileReportService`. Single hottest
  path; root cause of most backend latency.
- **HIGH — Per-table rollups recomputed in-memory each request** (`evidence/SqlRepositoryService.java:176-233`):
  `getAllSchemasWithRollup` / `getAllTablesWithRollup` / `getTablesForSchemaWithRollup` iterate and sum
  per table with no cache, over an immutable-after-load dataset.
- **MED — `ParquetSqlRepository.getTableQueries()` is an unindexed full scan**
  (`evidence/sink/ParquetSqlRepository.java:45-46`) where `AnalyzedSqlRepository` builds an O(1)
  `TableReference` multimap index — same operation, very different cost by backend.
- **Architectural ceiling (note):** the whole analyzed corpus is held in heap per environment
  (`SqlRepositoryManager`). Fine for a tool; state it explicitly as a scaling limit.

### Architecture / coupling

- **MED-HIGH — Two graph stacks.** v1 (`graph/SqlGraphService`, `SqlGraphResource`: `/graph/all`,
  `/graph/schema/**`, `/graph/by-tag/**`) vs v2 (`graph/GraphV2Service`, `GraphV2Resource`:
  `/graph/v2/schema/**`). The `*-lens` frontend uses only v2. v1 is a live-mapped controller with no
  confirmed frontend caller — candidate dead API surface (verify server-side usage before deleting).
- **MED — Hardcoded infra URLs in a domain service.** `graph/SqlGraphService.java:32-35` compiles in
  `http://localhost:4200/...`, a `grafana.local` dashboard URL, and a `rundeck.local` job URL with an
  embedded UUID + inline SQL (carries its own `// todo refactor`). Belongs in config.
- **MED-HIGH — Triplicated "group-by-digest + merge" logic** (`SqlEvidenceInputService.java:343-374`,
  `AnalyzedSqlRepository.java:67-91`, `CompositeSqlRepository.java:103-110`), and profiles are merged
  **three times** along the pipeline (import → repository ctor → per-read). DRY violation and the root
  of the perf item above.
- **MED — Large classes:** `analysis/JoinColumnAnalyzer.java` (~490 LOC), `evidence/SqlEvidenceInputService.java`
  (~453 LOC, orchestration + threads + parsing heuristics + merging + Parquet sink).
- **Positive:** the Avro `extensions/` (Manifold) pattern is applied consistently — a strength, not debt.

### Testing

- **HIGH — Zero integration/web/persistence tests.** No `@SpringBootTest`, `@WebMvcTest`, `@DataJpaTest`,
  or `MockMvc` anywhere; all 25 tests are plain Mockito unit tests. Bean wiring, every REST endpoint,
  JSON/Avro serialization, and JPA paths are unverified.
- **HIGH — Critical classes untested:** `SqlEvidenceInputService` (import/merge/thread pipeline) and
  `SqlRepositoryService` (central read path) have no tests; 18 `@RestController`s, only 2 have (unit-style)
  tests. Coverage is concentrated in `analysis/` (the pure-logic layer).

### Correctness / latent bugs

- **HIGH — Unguarded nullable `getRuntimeMetrics()` → NPE.** `runtimeMetrics` is a nullable Avro union
  (`models/src/main/avro/SqlExplorer.avdl:19`). `SqlRepositoryService` guards it in some places but
  dereferences it unguarded at `:97-100`, `:188`, `:223` — the `/table/*`, `/tables`, `/schemas` rollups
  NPE for any table whose queries include a profile with null metrics (e.g. analysis-skipped rows).
- **MED — PK assumed to be named `id`.** `SqlRepositoryService.java:164-165` (with its own alarmed TODO):
  join/related-table classification is wrong for any table whose PK isn't literally `id`.
- **MED — `containsEof` NPEs on a null exception message.** `SqlEvidenceInputService.java:312-320`
  calls `exception.getMessage().contains(...)` unguarded — inside the truncation-classification path.
- **LOW — Busy-wait import completion loop** (`SqlEvidenceInputService.java:148-161`); interrupt handling
  is correct, but `invokeAll`/`CompletionService` would be cleaner.
- **LOW — Unbounded `missingFieldExceptions` set** on a singleton (`SqlEvidenceInputService.java:67`) — grows
  across imports, never cleared.

### API / DTO consistency

- **MED — No `@ControllerAdvice`.** Only 4 of 18 resources handle errors; the rest fall through to a bare
  500. Inconsistent error contract across the API.
- **MED — Inconsistent REST conventions.** No common prefix; mixed singular/plural (`/table/**` vs
  `/tables/**`), two roots for the same entity (`/metadata/table/**` vs `/table/**`), versioning only on
  graph (`/graph/v2/**`), `text/plain` util endpoints alongside JSON ones.
- **MED — Frontend DTOs are hand-mirrored, no codegen.** See frontend §; the same drift risk exists on the
  Java side. An Avro→TS / OpenAPI generator would remove it.

---

## 2. Frontend (Angular)

> **Type-safety debt was addressed this session** (see [Recently addressed](#recently-addressed)) —
> the service layer and call sites are now typed and `any` is gone. Remaining items below stand.

- **HIGH — Testing is effectively non-existent.** All 28 `.spec.ts` are Angular CLI stubs (24 are
  byte-identical "should create"). Effective business-logic coverage is ~0%; the two most complex files
  (`queries-workbench`, `graph-lens`) have no spec.
- **MED-HIGH — Old-vs-new duplication (half-finished "lens" migration).** Both are routed:
  `table-lens` vs `table-view`; `graph-lens` vs `table-graph(-loader)`; `schema-tree` vs `schema-list`.
  Two HTTP services (`analyzed-sql.service`, `schema-lens.service`) hit overlapping backend endpoints.
  Deleting the superseded set removes a large maintenance surface (and the last of the legacy `.css` and
  shape-mismatch bugs) in one move — pending confirmation the `*-lens` versions fully replace them.
- **HIGH — Silent failures / no error UX.** ~54 of ~60 `.subscribe()` calls have no error handler; the
  handful that do just clear a spinner (`error: () => this.loading = false`) with no user-facing message.
  A global HTTP error strategy (interceptor + the already-configured `MatSnackBar`) is missing.
- **MED — Unmanaged subscriptions (leak risk).** Only ~4 components implement `OnDestroy`/unsubscribe;
  ~14 subscribe to router params / service observables without teardown. No `takeUntilDestroyed`; the
  `async` pipe is used in only one template.
- **MED — God components with direct DOM work.** `queries-workbench.component.ts` (~510 LOC) and
  `graph-lens.component.ts` (~486 LOC) do raw `document`/canvas manipulation (resize drag, canvas render)
  — untestable and SSR-hostile; the resize logic belongs in a directive.
- **MED — Nested subscribes (RxJS anti-pattern).** `sql-view.component.ts` and `table-lens.component.ts`
  fan out `subscribe` inside `subscribe` instead of `switchMap`/`forkJoin`.
- **MED — Duplicated endpoint base.** `const endpoint = '/api/sql-explorer/'` is copy-pasted in 6 services;
  no shared base-URL token. HTTP construction style is inconsistent (concat vs template literals).
- **MED — Mixed `.css`/`.scss`.** 6 (legacy) components still use `.css`; the rest `.scss`.
- **LOW — Dead/scaffolding code.** `user.service.ts` is an empty injected stub; the `/note` route
  (`main.ts`) ships placeholder markdown with an empty `author: {}`; stray `console.log` in
  `admin-source-list`; `main.ts` bootstrap `.catch(err => console.log(err))` swallows startup errors.

---

## 3. Build, CI, Infra, Config

- **HIGH — No CI/CD.** No `.github/workflows`, `.gitlab-ci.yml`, `Jenkinsfile`, etc. Only a hand-run
  `smoke-test.sh`. Nothing enforces tests/lint/spotbugs/dependency-check — every gate below is advisory.
- **HIGH — Stack traces exposed in all environments.** `src/main/resources/application.yml:20-23`:
  `include-exception: true` + `include-stacktrace: always` in the **base** profile → info disclosure on
  any deployed instance.
- **HIGH — Internal hostnames committed.** `.env.example` and `docs/LOCAL_CONFIG.md` contain a
  real staging RDS endpoint and bastion host, present in git history since an early commit. (Working-tree
  `.env` holds live-looking creds but was never committed and is correctly gitignored — rotate if the
  machine is shared.)
- **HIGH (cumulative) — Frontend toolchain is Angular 21 riding on abandoned tooling.** `ui/package.json`
  mixes `@angular/* ^21` with `core-js ^2` (EOL), `jquery`/`bootstrap 4`/`popper.js 1`,
  `@typescript-eslint ^4` + `eslint ^7` (several majors behind), `@types/node ^12` (Docker builds on
  node:22), and an unbounded `highlight.js ">=9"`. Dead config still committed: `ui/.angular-cli.json`,
  `ui/ngcc.config.js` (pre-Ivy), `ui/protractor.conf.js` + `ui/e2e/*` (project actually uses Playwright).
  `.npmrc` sets `legacy-peer-deps=true`, masking the conflicts.
- **MED — `ng test` target is misconfigured.** `ui/angular.json` references `src/karma.conf.js` and
  `styles.css`; actual files are `ui/karma.conf.js` and `ui/src/styles.css`. With no CI, unit tests
  likely never run.
- **MED — Spotbugs is near-toothless.** `build.gradle:44-59` disables XML reports; `spotbugs-exclude-filter.xml`
  blanket-suppresses `EI/EI2/MS/CT/UuF/RCN` under a misleading "try-with-resources false positive" comment
  and references classes that no longer exist (stale).
- **~~MED — `.nvmrc` says Node 16.20.2 (EOL); Dockerfile builds on node:22~~ — fixed 2026-07-19**
  (`ui/.nvmrc` now pins `22`, matching the Docker build).
- **MED — `open-in-view: true`** (`application.yml:19`) — keeps a DB session open for the whole request
  render; hurts pool behavior.
- **MED — No Gradle version catalog.** Versions hardcoded across `build.gradle` and `models/build.gradle`
  (legacy `apply plugin`), OWASP `dependencyCheck` pinned to `7.1.0.1` (the scanner itself is outdated),
  `suppression.xml` is an empty stub. Image pinning inconsistent: `mysql:8.4.4` pinned, but `ui/Dockerfile`
  uses floating `node:22` / `nginx`.
- **MED — Docker hardening gaps.** `MYSQL_ROOT_PASSWORD: password` in `docker-compose.yml` and committed
  `application-secrets.yml` (`root`/`password`) — local-only but copy-paste seeds. Backend
  `src/main/docker/Dockerfile` runs as **root** (hardening lines commented out with a `# todo`), no
  healthcheck; compose uses `depends_on` only (shared-tags works around it with a poll loop).
- **LOW — Misc.** OpenAPI plugin commented out (`// todo, get this working`); `bootJar`
  `duplicatesStrategy = WARN` masks duplicate jar entries; heavy Hadoop/Parquet footprint for local file
  I/O; `auth/` domain is a documented non-feature (`auth/TODO.md`) yet ships `User*`/`/user` classes.

---

## 4. Docs & repo hygiene

- **~~MED — `CLAUDE.md` is materially stale~~ — fixed 2026-07-19.** Framework version, lint/e2e tooling,
  `ENVIRONMENT_PROFILE`, the route table, and backend class names (`SqlParser`, `SchemaResource`) are now
  corrected.
- **~~MED — Planning docs + an 87 KB HTML mockup left in-tree~~ — fixed 2026-07-19.** Removed the five
  completed/abandoned planning artifacts (`schema-lens.html`, `SCHEMA_LENS_PLAN.md`, `DATA_ENVIRONMENTS.md`,
  `NEXT_STEPS.md`, `MIGRATION_ANALYSIS.md`) — both the schema-lens and environments plans are fully
  implemented; the other two were abandoned brainstorms. `DATA_ENVIRONMENTS.md` contained useful
  environment architecture notes; if as-built docs are wanted, reconstruct them from the code rather than
  the stale plan.
- **LOW — Repo hygiene** (mostly clean): build artifacts, `.env`, `.gradle`, `.ssh.pid` are correctly
  gitignored. Leftovers: `schemas/schema-dump.sql` is a 1-byte placeholder mounted as an init script;
  `.idea/runConfigurations/18185.xml` is an opaquely-named tracked run config.

---

## Recently addressed

Fixed while compiling this report (context so they aren't re-flagged):

- **Frontend type safety** — the untyped `analyzed-sql`/`source` services and ~60 `any` call sites were
  replaced with a shared `ui/src/app/api-types.ts` mirroring the backend DTOs; every consumer is typed and
  the production build's AOT template checks pass. This also fixed several latent legacy bugs
  (`sql-view`/`table-graph` treating `TableReference` objects as strings; `sql-summary`/`small-sql-view`
  null-unsafe `runtimeMetrics` and `JoinColumns` array-indexing). _Note:_ these types are still
  **hand-mirrored** — the DTO-drift risk (backend M22 / frontend) remains until codegen exists.
- **`QuerySearchServiceTest.tagFiltersMatchAnyAllAndUntagged`** — a failing test was repaired.
- **Docs/config low-hanging fruit** — `CLAUDE.md` corrected to match the code, five completed/abandoned
  planning docs removed, and `ui/.nvmrc` aligned with the Node 22 Docker build.

---

## Suggested sequencing

1. **CI first** (#1) — it's the multiplier that makes every other gate enforceable. Wire build + test +
   lint + spotbugs + dependency-check on PR; fix the Karma paths (§3) and de-neuter spotbugs so the gates
   mean something.
2. **Security/config quick wins** — turn off `include-stacktrace: always` (#4); purge internal hostnames
   from tracked files and history (#5).
3. **Backend perf + correctness** (same root) — cache/precompute the merged profile list so
   `getAllAnalyzedSql()` and the rollups stop rebuilding per request (#2), and fix the nullable-metrics
   NPE (#3) in the same pass.
4. **Kill the old-vs-new duplication** (#7) — deleting the superseded legacy components removes a large
   slice of frontend debt at once.
5. **Test coverage** — a thin `@WebMvcTest`/`@SpringBootTest` layer for the import pipeline and REST
   resources; real specs for the two god components and the typed services.
6. **Toolchain + docs cleanup** — modernize the frontend toolchain and remove dead config; correct
   `CLAUDE.md` and relocate the planning docs/mockup.
