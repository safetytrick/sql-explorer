# Environments & DataSources — Implementation Plan

SQL Explorer is currently a single-tenant application: it connects to one metadata
database (the Spring `metadata` DataSource bean), loads that schema once at startup,
and aggregates all loaded SQL sources into a single global repository. This plan
introduces an **Environment** abstraction so the app can hold several environments
(staging, prod, prod-us, …), each containing one or more database servers, and lets
the user pick which environment backs their queries.

## Domain model

```
Environment (e.g. "staging", "prod-us")
 └── DatabaseServer (1..n per environment)
      ├── ConnectionInfo       — jdbcUrl, username, password (owned by us, not Spring)
      ├── SqlSchemaRepository  — schema cache, lazily loaded from this server
      └── SqlSourceConfig (1..n) — where the server's SQL evidence comes from:
                                   x$statement_analysis, performance schema files, …
```

- **Environment** — a named deployment context. Owns its servers, a composite
  `SqlRepository` over the SQL loaded from those servers, and a composite
  `SqlSchemaRepository` over their schemas.
- **DatabaseServer** — one MySQL server. Owns its JDBC connection details, builds its
  own `HikariDataSource` lazily (Spring does not manage it), and owns the schema
  loaded from that server.
- **SqlSourceConfig** — describes a data source of SQL run against that server.
  Subsumes today's `x-statement-analysis.hosts` config and the
  `performance-schema-file-loader` directory scan.

### Why the schema must be per-server

The analysis pipeline (`ColumnTableResolver`, `UnknownSchemaAnalyzer`,
`JoinAcrossTypesAnalyzer`) resolves columns to tables using `SqlSchemaService`, a
singleton backed by the one global schema cache in `SchemaLoadingService`
(see the `todo remove this global scope` at `SchemaLoadingService.java:36`).
Today a query captured on staging is resolved against whatever schema the app booted
with. Under this design, analysis of a server's SQL always uses that server's schema.

## Current state (what changes)

| Today                                                                              | After                                                                                      |
|------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------|
| `MetadataDataSource` Spring bean from `datasources.metadata.*`                     | Deleted. `DatabaseServer` builds/owns its `HikariDataSource` from `ConnectionInfo`         |
| `SchemaLoadingService` caches one global schema in `@PostConstruct`                | Per-server `LoadedSchema` built lazily by `SchemaRepositoryFactory`                        |
| Schema fetcher services inject `@Named("metadata") DataSource`                     | Fetcher factory methods take a `DataSource` parameter                                      |
| `SqlSchemaService` singleton injected into analyzers                               | `SqlSchemaRepository` threaded through the analysis call chain                             |
| `SqlRepositoryManager` holds one global repository list                            | Each `Environment` owns its repositories; a request-scoped context resolves the active one |
| `XStatementAnalysisLoader` / `PerformanceSchemaFileLoader` run at `@PostConstruct` | Source configs under a server; loaded via `POST /environments/{name}/load`                 |
| No environment concept in the UI                                                   | Navbar selector; `HttpInterceptor` sends the selection on every request                    |

Connecting at startup goes away entirely: the app boots without touching any
metadata database, and schemas/sources load on demand. This also removes the
`@DependsOnDatabaseInitialization` ordering constraints.

---

## Phase 1 — Self-managed connections & per-server schema

Goal: remove the Spring-managed `metadata` DataSource and make schema loading a
factory operation, while behavior stays identical via a single implicit "default"
environment built from the existing `datasources.metadata` config.

New package `environment/`:

- `ConnectionInfo` — record: `jdbcUrl`, `username`, `password`.
- `DatabaseServer` — name + `ConnectionInfo`; lazily builds a `HikariDataSource`
  (small pool, `minimumIdle=0` — schema introspection is bursty); lazily builds its
  `SqlSchemaRepository` via `SchemaRepositoryFactory`; `AutoCloseable` (closes the
  pool on eviction/refresh).
- `Environment` — name + `List<DatabaseServer>`; exposes a composite
  `SqlSchemaRepository` over its servers.
- `EnvironmentRegistry` (`@Service`) — `get(name)`, `list()`, `getDefault()`.
  In phase 1 it holds exactly one environment assembled from
  `datasources.metadata.*`.

Changes in `schema/`:

1. `TableColumnService`, `IndexLoadingService`, `ForeignKeyLoadingService`,
   `ColumnEncodingService`, `CreateStatementService`: remove the
   `@Named(MetadataDataSource.NAME)` constructor injection; the fetcher factory
   methods (`getColumnFetcher()`, `getIndicesFetcher()`, …) take a
   `DataSource` parameter instead. The services stay stateless Spring singletons.
2. New `LoadedSchema` — holds the state currently living in `SchemaLoadingService`
   (`tableStructures`, `byTableReference`, `byTableName`) and implements
   `SqlSchemaRepository`. One instance per server, immutable after load.
3. New `SchemaRepositoryFactory` (`@Service`) — owns the orchestration logic from
   `SchemaLoadingService.initializeTableStructures()`:
   `LoadedSchema load(DataSource ds)`.
4. Delete `MetadataDataSource` and `SchemaLoadingService` (its loading logic moves
   to the factory, its cache moves to `LoadedSchema`).
5. `SqlSchemaService` becomes a thin delegate that resolves the active environment's
   composite `SqlSchemaRepository` (phase 1: always the default environment). This
   keeps `SchemaResource`, `SqlRepositoryService`, `SqlGraphService`, and the
   analyzers compiling unchanged until phases 2–3 retire it.
6. `CreateStatementService`'s lazy `SHOW CREATE TABLE` cache must key by
   server, not just table, once multiple servers exist — move its cache into
   `LoadedSchema`/`DatabaseServer` scope now to avoid a latent bug.

Composite schema semantics: with one server per environment this is a pass-through.
With several, `getTablesByName` concatenates results;
`findSchemaForTablesAndColumns` runs per-server. Document that same-named schemas on
two servers in one environment are unsupported for now (open question below).

Acceptance: `./gradlew test` green; app boots and behaves exactly as today against
docker-local; no `@Named("metadata")` injections remain.

## Phase 2 — Thread the schema through the analysis pipeline

Goal: analysis of a server's SQL uses that server's schema, not a global singleton.

1. `SqlAnalyzer.analyze(Statement, SqlProfile.Builder)` gains a context parameter:
   `analyze(Statement, SqlProfile.Builder, SqlSchemaRepository schema)`.
   Analyzers that don't need schema ignore it. (Alternative — constructing analyzer
   sets per import — rejected: the analyzers are stateless, and the interface change
   is mechanical.)
2. `SqlAnalysisService.analyzeSql(sql, builder)` →
   `analyzeSql(sql, builder, SqlSchemaRepository)`.
3. `ColumnTableResolver`, `UnknownSchemaAnalyzer`, `JoinAcrossTypesAnalyzer`: drop
   the injected `SqlSchemaService`; use the parameter.
4. `SqlEvidenceInputService.fromPerformanceSchema(source, inputStream)` gains the
   schema parameter and passes it through `processRow`. Callers
   (`SqlRepositoryManager.loadFromSqlSources`) supply the owning server's schema —
   in phase 2 still the default environment's schema, so behavior is unchanged
   until phase 3 associates sources with servers.
5. Tests: the varargs `SqlAnalysisService(SqlAnalyzer...)` test constructor and
   analyzer unit tests get an explicit (stub) `SqlSchemaRepository`.

Acceptance: no production class injects `SqlSchemaService` for analysis purposes;
`./gradlew test` green.

## Phase 3 — Per-environment repositories & request scoping

Goal: each environment owns its SQL repositories; REST requests resolve repositories
through a selected environment. Existing endpoint paths do not change.

1. **Config-defined environments.** New `@ConfigurationProperties(prefix = "environments")`:

   ```yaml
   environments:
     staging:
       servers:
         staging-db-primary:
           jdbcUrl: jdbc:mysql://...
           username: ...
           password: ...    # via the secrets profile, as today
           sources:
             - type: x-statement-analysis
             - type: performance-schema-files
               directory: /performance-schema
   ```

   `EnvironmentRegistry` is built from this. If the legacy `datasources.metadata.*`
   keys are present and `environments` is absent, synthesize a `default`
   environment from them (back-compat for docker-local until its config migrates).

2. **Repositories move into `Environment`.** `Environment` gets the repository
   list + `CompositeSqlRepository` currently in `SqlRepositoryManager`. Loading
   sources for environment E replaces only E's repositories.
   `SqlRepositoryManager` either becomes the per-environment holder object or is
   deleted in favor of methods on `Environment`; the load orchestration
   (`loadFromSqlSources`) stays but takes the target environment.

3. **Source loaders become source-config types.** `XStatementAnalysisLoader` and
   `PerformanceSchemaFileLoader` lose their `@PostConstruct`/`@ConditionalOnProperty`
   bootstrapping; their logic becomes `SqlSource` factories keyed by
   `SqlSourceConfig.type`, invoked during environment load. The
   `x-statement-analysis.hosts` and `performance-schema-file-loader` config keys are
   superseded by `environments.*.servers.*.sources` (keep parsing the old keys into
   the default environment during the transition, then remove).

4. **Request scoping.** The UI sends `X-Sql-Explorer-Environment: <name>` on every
   request. A `OncePerRequestFilter` resolves it against `EnvironmentRegistry`
   (falling back to the default; unknown name → 404) into a request-scoped
   `EnvironmentContext`. `SqlRepositoryService`, `SqlGraphService`, and
   `SchemaResource` resolve `SqlRepository` / `SqlSchemaRepository` from the context
   instead of injecting `SqlRepositoryManager` / `SqlSchemaService`. Header chosen
   over path params so no existing route, bookmark, or UI service changes.

5. **New REST endpoints** (`environment/EnvironmentResource`):
   - `GET /environments` — names, servers, load state (schema loaded? sources
     loaded? counts, timestamps).
   - `POST /environments/{name}/load` — (re)load schema + sources for that
     environment; returns `SqlImportStats`. Subsumes `POST /sources/load` for
     server-attached sources; uploaded-file sources (`SqlSourceEntity`) remain
     loadable and are attributed to the selected environment.
   - `POST /environments/{name}/refresh-schema` — drop and reload per-server
     `LoadedSchema` (and close/rebuild the Hikari pools).

6. **Persistence touch-up.** `SourceRefEntity.environmentType` (the unused enum) is
   superseded by an `environmentName` string column so uploaded sources can be
   attributed to an environment. Full DB-backed environment CRUD (with encrypted
   credentials) is explicitly deferred — see Open questions.

Acceptance: two environments configured in docker-local (e.g. the existing DB twice
under different names); switching the header switches results; loading env A does
not disturb env B.

## Phase 4 — UI environment selector

1. `EnvironmentService` (mirrors `SourceService`): `getEnvironments()`,
   `loadEnvironment(name)`, and a `BehaviorSubject<string>` for the current
   selection, persisted to `localStorage`.
2. `HttpInterceptor` adds `X-Sql-Explorer-Environment` to every `/api/sql-explorer/`
   request.
3. Navbar `mat-select` bound to the service. On change, route to the schema list
   (cheap, avoids stale per-view state) — views that should survive a switch can
   instead subscribe to the selection observable the way they subscribe to route
   params.
4. `AdminSourceListComponent` becomes environment-scoped: shows the selected
   environment's servers, their sources, and load state; "apply" calls
   `POST /environments/{name}/load`.

Acceptance: user can switch environments from the navbar and see different
schemas/queries; selection survives reload.

---

## Open questions / deferred decisions

- **Credential storage & runtime CRUD.** Phase 3 keeps environments config-defined
  (credentials via the existing secrets profile). DB-backed environment management
  needs credentials encrypted at rest (e.g. AES-GCM, key from an env var) and an
  admin UI — deferred until config-defined environments prove insufficient.
- **Same schema name on two servers in one environment.** The composite
  `SqlSchemaRepository` currently concatenates; first match wins on conflict. Decide
  whether to qualify by server name or forbid the configuration.
- **`CompositeSqlRepository.getSource()`** still returns only the first repository's
  source (`todo implement composite source`) — worth fixing in phase 3 when sources
  become server-attributed.
- **Cross-environment diffing** (same query on staging vs prod) is a natural
  follow-on once environments exist, but out of scope here.

## File-level index of touched code

| Area | Files |
|---|---|
| Delete | `schema/MetadataDataSource.java`, `schema/SchemaLoadingService.java` (logic relocated) |
| New (`environment/`) | `ConnectionInfo`, `DatabaseServer`, `Environment`, `EnvironmentRegistry`, `EnvironmentContext`, `EnvironmentResource`, `EnvironmentsConfig`, `SchemaRepositoryFactory`, `LoadedSchema` |
| Schema fetchers | `TableColumnService`, `IndexLoadingService`, `ForeignKeyLoadingService`, `ColumnEncodingService`, `CreateStatementService` — DataSource as method param |
| Analysis | `SqlAnalyzer` (interface), `SqlAnalysisService`, `ColumnTableResolver`, `UnknownSchemaAnalyzer`, `JoinAcrossTypesAnalyzer` |
| Evidence | `SqlEvidenceInputService`, `SqlRepositoryManager`, `SqlRepositoryService`, `SqlSourceResource`, `XStatementAnalysisLoader`, `PerformanceSchemaFileLoader`, `SourceRefEntity` |
| REST | `SchemaResource`, `SqlProfileResource` (via `SqlRepositoryService`), `graph/SqlGraphService` |
| Config | `application.yml`, `src/main/docker-local/config/application*.yml` |
| UI | `environment.service.ts` (new), HTTP interceptor (new), navbar component, `admin-source-list/` |
