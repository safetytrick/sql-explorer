# SQL Explorer — Technical Debt Report

_Originally compiled 2026-07-19; resolved entries pruned 2026-08-15;
**re-verified 2026-08-28** (`sqlx-zoy`). Covers the Java backend (`src/`), the
Angular frontend (`ui/`), and build/CI/infra/config. Findings are grouped by
area and ranked by severity (HIGH / MED / LOW). File paths and line numbers
will drift._

> **What the 2026-08-28 pass changed.** Every entry below was checked against
> the tree rather than assumed. Four findings were promoted to beads, where
> they compete for time with everything else; the rest are deliberately left
> here unscheduled. Two entries were wrong and are corrected in place — the
> nullable-metrics NPE is latent rather than live, and the lens-migration
> duplication is gone. Nothing was deleted merely for being old.
>
> | Promoted | Bead |
> |---|---|
> | Cache `getAllAnalyzedSql()` + guard the nullable metrics | `sqlx-czd` |
> | Version the normalization scheme | `sqlx-2oq` |
> | Component stylesheets override the global vocabulary | `sqlx-n77` |
> | Retire the abandoned frontend toolchain corners | `sqlx-ila` |
>
> Left unscheduled on purpose: no integration tests, no `@ControllerAdvice`,
> the two graph stacks, triplicated merge logic, large analyzer classes, the
> silent-failure/unmanaged-subscription frontend items, `open-in-view`,
> `include-stacktrace`, and the REST-convention inconsistency. All still true,
> none currently worth displacing what is in `bd ready`. Filing them as beads
> would be this inventory again with different storage.

## How to read this

This is a point-in-time inventory, not a work order. Severity reflects blast
radius × likelihood, not effort. The **Top priorities** table is the
recommended order of attack; everything else is detail to pull from when
scheduling the work.

Entries are deleted as they are fixed rather than struck through — git history
has what was here. If something below turns out to be already done, delete it.

---

## Top priorities (cross-cutting)

| # | Finding | Area | Severity |
|---|---------|------|----------|
| 1 | **`getAllAnalyzedSql()` rebuilds a full group-by + merge + sort on every request** — hit by search, schema-tree, graph, and 9 report methods | Backend | HIGH |
| 2 | **Unguarded nullable `RuntimeMetrics` → NPE on the `/table`, `/tables`, `/schemas` rollup endpoints** | Backend | HIGH |
| 3 | **Error responses leak stack traces in every environment** (`include-stacktrace: always` in base config) | Config | HIGH |
| 4 | **Internal infra hostnames in git history** (staging RDS + bastion; working tree is scrubbed) | Infra | HIGH |
| 5 | **Thin automated test coverage** — no backend integration/web tests; half the UI specs are still CLI stubs | Both | HIGH |
| 6 | **Analyzer loop swallows all exceptions** to a debug log — analyzer bugs silently degrade data quality | Backend | HIGH |
| 7 | **Old-vs-new duplication** — `table-view` and `table-graph` still routed alongside their lens replacements | Frontend | MED |

---

## 1. Backend (Java)

### Error handling

- **PARTLY ADDRESSED (2026-08-28, `sqlx-igm`) — Analyzer loop swallows all exceptions.** The swallow
  itself remains, deliberately: one analyzer failing should not lose the rest of the analysis. What
  changed is that it is no longer *silent* — `AnalysisFailureRecorder` captures the statement, the
  session schema and the referenced tables' DDL per failure, and `/analysis-failures/fixtures`
  renders them as paste-ready tests. The single `analysisFailureReason` string still keeps only the
  last failure; the recorder accumulates. Original entry: `analysis/SqlAnalysisService.java:47-54` runs
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

- ~~**MED-HIGH — Two graph stacks.**~~ Resolved 2026-11-05 (sqlx-aoy.9): the v1 graph API
  (`SqlGraphService`, `SqlGraphResource`: `/graph/all`, `/graph/schema/**`, `/graph/by-tag/**`) is
  deleted with `TableGraph*`, its only caller. v2 (`GraphV2Service`) is the one graph.
- ~~**MED — Hardcoded infra URLs in a domain service.**~~ Went with the v1 graph service above,
  which held them; nothing was refactored. The idea lives on in
  [plans/grafana-integration.md](plans/grafana-integration.md).
- **MED-HIGH — Triplicated "group-by-digest + merge" logic** (`SqlEvidenceInputService.java:343-374`,
  `AnalyzedSqlRepository.java:67-91`, `CompositeSqlRepository.java:103-110`), and profiles are merged
  **three times** along the pipeline (import → repository ctor → per-read). DRY violation and the root
  of the perf item above.
- **MED — Large classes:** `analysis/JoinColumnAnalyzer.java` (~490 LOC), `evidence/SqlEvidenceInputService.java`
  (~453 LOC, orchestration + threads + parsing heuristics + merging + Parquet sink).
- **Positive:** the Avro `extensions/` (Manifold) pattern is applied consistently — a strength, not debt.

### Testing

- **HIGH — Zero integration/web/persistence tests.** No `@SpringBootTest`, `@WebMvcTest`, `@DataJpaTest`,
  or `MockMvc` anywhere; the backend suite is plain Mockito unit tests. Bean wiring, every REST endpoint,
  JSON/Avro serialization, and JPA paths are unverified.
- **HIGH — Critical classes untested:** `SqlEvidenceInputService` (import/merge/thread pipeline) and
  `SqlRepositoryService` (central read path) have no tests; of 18 `@RestController`s, only 2 have
  (unit-style) tests. Coverage is concentrated in `analysis/` (the pure-logic layer).

### Correctness / latent bugs

- **LATENT, not HIGH (corrected 2026-08-28) — Unguarded nullable `getRuntimeMetrics()` → NPE.**
  Four unguarded sites confirmed in `SqlRepositoryService` (lines 98, 188, 207, 238; line 143 is
  guarded and shows the shape). But measured across 1,000 live profiles, **zero** have a null
  `runtimeMetrics` — `complete()` and `MergedSqlProfile` both populate it. So this is a guard against
  a state the pipeline does not currently produce, not a live crash. Worth fixing because nothing
  *enforces* that; not worth treating as an emergency. Scheduled as `sqlx-czd`.
  Original entry: `runtimeMetrics` is a nullable Avro union
  (`models/src/main/avro/SqlExplorer.avdl:19`). `SqlRepositoryService` guards it in some places (`:143`)
  but dereferences it unguarded at `:98`, `:188`, `:207`, `:238` — the `/table/*`, `/tables`, `/schemas`
  rollups NPE for any table whose queries include a profile with null metrics (e.g. analysis-skipped rows).
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
- **MED — Frontend DTOs are hand-mirrored, no codegen.** `ui/src/app/api-types.ts` mirrors the backend
  DTOs by hand; nothing detects drift. An Avro→TS / OpenAPI generator would remove the risk.

---

## 2. Frontend (Angular)

- **HIGH — Test coverage is thin.** (Re-checked 2026-08-28: still 37 `.spec.ts` files, 18 of them CLI
  stubs — the ratio has barely moved, though the real specs added since are substantially deeper.)
  37 `.spec.ts` files, but 18 are still Angular CLI "should create"
  stubs. Real coverage exists now in the pure-logic units (`search-url`, `date-math`, `refs`,
  `index-relevance`, `tag-filter-url`, `tag-members`, `tag-field`), and that is the pattern worth
  extending; the two most complex components (`queries-workbench`, `graph-lens`) still have no spec.
- **RESOLVED 2026-08-28 (`sqlx-b50`) — Old-vs-new duplication (the tail of the lens migration).**
  `TableViewComponent` is retired, along with `TableLinkComponent` and `CallbackPipePipe`, which had
  no other consumers. Its two routes redirect to the lens rather than 404. The graph half is *not*
  done and is not duplication either: the legacy graph does things the lens graph cannot, so it is
  parity work, tracked as `sqlx-m0x` (deferred). Original entry: `table-lens` vs `table-view`, and
  `graph-lens` vs `table-graph(-loader)` — both legacy routes still live. Two HTTP services
  (`analyzed-sql.service`, `schema-lens.service`) hit overlapping backend endpoints. See
  `docs/plans/legacy-ui.md`; this is now a deletion decision, not a restyle.
- **HIGH — Silent failures / no error UX.** ~54 of ~60 `.subscribe()` calls have no error handler; the
  handful that do just clear a spinner (`error: () => this.loading = false`) with no user-facing message.
  A global HTTP error strategy (interceptor + a toast) is missing.
- **MED — Unmanaged subscriptions (leak risk).** Only a handful of components implement
  `OnDestroy`/unsubscribe; many subscribe to router params / service observables without teardown. No
  `takeUntilDestroyed`; the `async` pipe is used in only one template.
- **MED — God components with direct DOM work.** `queries-workbench.component.ts` (~510 LOC) and
  `graph-lens.component.ts` (~486 LOC) do raw `document`/canvas manipulation (resize drag, canvas render)
  — untestable and SSR-hostile; the resize logic belongs in a directive.
- **MED — Nested subscribes (RxJS anti-pattern).** `table-lens.component.ts` fans out `subscribe` inside
  `subscribe` instead of `switchMap`/`forkJoin`.
- **MED — Duplicated endpoint base.** `const endpoint = '/api/sql-explorer/'` is copy-pasted across
  services; no shared base-URL token. HTTP construction style is inconsistent (concat vs template literals).
- **LOW — Two `.css` components left.** `app.component.css` and `note-view.component.css`; everything
  else is `.scss`.
- **LOW — Dead/scaffolding code.** `user.service.ts` is an empty injected stub; the `/note` route
  (`main.ts:159`) ships placeholder markdown with an empty `author: {}`; `main.ts` bootstrap
  `.catch(err => console.log(err))` swallows startup errors.

---

### Found by the 2026-08-28 pass

- **MED — Component stylesheets override the global lens vocabulary rather than using it.**
  Eleven local copies redeclare the *same* properties as the global class, and a component-scoped
  copy wins on specificity, so the global is decorative for those components: `.tag-chip`
  (schema-tree, queries-workbench, tagged-sql-view, tag-field), `.data-table` (table-lens,
  sql-view), `.lens-card` (table-lens, sql-view), `.view-header` (table-lens, sql-view), `.tri-btn`
  (queries-workbench, table-lens), `.table-scroll` (explain-view, table-lens, sql-view).
  `not-found.component.scss` is the counter-example — it extends `.lens-card` without redeclaring
  anything. Scheduled as `sqlx-n77`.

- **MED — Three component stylesheets exceed the 6kB `anyComponentStyle` budget**:
  `queries-workbench` (12.58kB), `sql-view` (7.76kB), `table-lens` (7.62kB). Warning-only — the
  budget sets `maximumWarning` and no `maximumError` — so it never fails a build and has been
  ignored accordingly. Largely the same root cause as the entry above.

- **NOTE — Normalizer versioning is now due rather than optional.** `docs/plans/normalization-versioning.md`
  was safe to leave unscheduled while no digest was stored durably. `query_example` rows now store
  one. Scheduled as `sqlx-2oq`.

## 3. Build, CI, Infra, Config

- **HIGH — Stack traces exposed in all environments.** `src/main/resources/application.yml:22-23`:
  `include-exception: true` + `include-stacktrace: always` in the **base** profile → info disclosure on
  any deployed instance.
- **HIGH — Internal hostnames in git history.** The working tree was scrubbed 2026-08-02 (placeholders in
  `.env.example`, example server keys renamed), but the strings remain in **history** until the rewrite in
  `docs/plans/history-rewrite.md` runs. (Working-tree `.env` holds live-looking creds but was never
  committed and is correctly gitignored — rotate if the machine is shared.)
- **MED — Frontend toolchain has abandoned corners.** Re-verified 2026-08-28, every pin below still
  present; scheduled as `sqlx-ila`. Note `protractor.conf.js` survives although protractor itself is
  no longer a dependency at all. `ui/package.json` still carries `core-js ^2` (EOL),
  `@types/node ^12` (Docker builds on node:22), `@typescript-eslint ^4` + `eslint ^7` (several majors
  behind), and an unbounded `highlight.js ">=9"`. Dead config still committed: `ui/.angular-cli.json`,
  `ui/ngcc.config.js` (pre-Ivy), `ui/protractor.conf.js` + `ui/e2e/*` (the project uses Playwright).
  `ui/.npmrc` sets `legacy-peer-deps=true`, masking conflicts.
- **MED — Spotbugs is near-toothless.** One deliberate, documented exception was added 2026-08-28:
  `LiveExplainService.explain` is exempted from `SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE`, because
  building `EXPLAIN <statement>` is that method's purpose and a PreparedStatement cannot parameterize
  a whole statement. The filter records the condition that would invalidate it.
  `build.gradle:44-59` disables XML reports; `spotbugs-exclude-filter.xml`
  blanket-suppresses `EI/EI2/MS/CT/UuF/RCN` under a misleading "try-with-resources false positive" comment
  and references classes that no longer exist (stale).
- **MED — `open-in-view: true`** (`application.yml:19`) — keeps a DB session open for the whole request
  render; hurts pool behavior.
- **MED — No Gradle version catalog.** Versions hardcoded across `build.gradle` and `models/build.gradle`
  (legacy `apply plugin`), OWASP `dependencyCheck` pinned to `7.1.0.1` (the scanner itself is outdated),
  `suppression.xml` is an empty stub. Image pinning inconsistent: `mysql:8.4.4` pinned, but `ui/Dockerfile`
  uses floating `node:22` / `nginx`.
- **MED — Docker hardening gaps.** `MYSQL_ROOT_PASSWORD: password` in `docker-compose.yml` and committed
  `application-secrets.yml` (`root`/`password`) — local-only but copy-paste seeds. No healthcheck; compose
  uses `depends_on` only (shared-tags works around it with a poll loop).
- **LOW — Misc.** OpenAPI plugin commented out (`// todo, get this working`); `bootJar`
  `duplicatesStrategy = WARN` masks duplicate jar entries; heavy Hadoop/Parquet footprint for local file
  I/O; `auth/` domain is a documented non-feature (`auth/TODO.md`) yet ships `User*`/`/user` classes.
- **LOW — Repo hygiene** (mostly clean): build artifacts, `.env`, `.gradle` are correctly gitignored.
  Leftovers: `schemas/schema-dump.sql` is a 1-byte placeholder mounted as an init script;
  `.idea/runConfigurations/18185.xml` is an opaquely-named tracked run config.

---

## Suggested sequencing

1. **Security/config quick wins** — turn off `include-stacktrace: always` (#3); purge internal hostnames
   from history (#4).
2. **Backend perf + correctness** (same root) — cache/precompute the merged profile list so
   `getAllAnalyzedSql()` and the rollups stop rebuilding per request (#1), and fix the nullable-metrics
   NPE (#2) in the same pass.
3. **Kill the remaining old-vs-new duplication** (#7) — deleting `table-view` and `table-graph` also
   settles whether the v1 graph API can go.
4. **Test coverage** — a thin `@WebMvcTest`/`@SpringBootTest` layer for the import pipeline and REST
   resources; real specs for the two god components, replacing the 19 remaining stubs.
5. **Toolchain cleanup** — modernize the frontend toolchain, remove the dead pre-Ivy/Protractor config,
   and de-neuter spotbugs so the CI gate means something.
