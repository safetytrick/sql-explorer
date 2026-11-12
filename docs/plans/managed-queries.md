# Managed queries: design

Design for epic `sqlx-tev`, written for `sqlx-tev.2`. The research behind it
(Redash, Grafana, Rundeck, Superset, Metabase) is `sqlx-tev.1`. This records the
decisions already made, what follows from them, and what is still open.

A **managed query** is a saved, parameterised SELECT. It runs against a
**datasource**, its result is kept, and it can be shown as a table or a chart.
Every run records who ran it and how. The feature is built so the database
enforces its safety, not the application.

## Decisions already made

| # | Decision | Made |
|---|----------|------|
| 1 | Build it ourselves, borrowing patterns from the tools above; embed none of them | research, 2026-11-10 |
| 2 | Results are stored as Parquet, one directory per job, with a size cap per result (configurable, default 1 MB) | user, 2026-11-10 |
| 3 | Run-as database users are created by the operator. sql-explorer checks their grants, audits every run and documents the setup | user, 2026-11-10 |
| 4 | Access control comes first (`sqlx-e13`, done apart from tokens) | user, 2026-11-10 |
| 5 | A saved query names a datasource and can be pointed at any other. A datasource carries its server, its run-as user and its permissions | user, 2026-11-10 |
| 6 | EXPLAIN before a run is optional and comes later (`sqlx-tev.9`) | user, 2026-11-10 |
| 7 | Datasources are defined in config, beside environments, with secrets from environment variables | user, 2026-11-11 |
| 8 | Shards get one datasource each in v1, with a config shorthand to declare them all at once | user, 2026-11-11 |
| 9 | A run against an environment served from its cache is refused; it never takes the environment live | user, 2026-11-11 |
| 10 | A primary can be made queryable, but only by saying so for that datasource | user, 2026-11-11 |
| 11 | Mostly standalone. The one shared piece is schema completion in the composer | user, 2026-11-10 |

Access control (`sqlx-e13.4`) already defines the three permissions this needs,
each held per datasource:

- `query:read` lets you see saved queries, runs and results (Viewer and up);
- `query:run` lets you run a saved query (Runner and up);
- `query:author` lets you write and edit SQL, including running SQL that isn't saved (Author and up).

## Module boundary

Decision 11 asks for a separate module. A separate Gradle subproject would mean
splitting the parts it uses (environments, auth, schema) out of the
application first, because the Boot jar would have to depend on the module and
the module on them. That refactor isn't worth doing before there is a feature
to protect.

**v1 is a package boundary that a test enforces.**

- **Backend:** everything lives under `com.github.safetytrick.sqlexplorer.managed`.
  - `managed` may use the environment API, auth and the schema repository.
  - Nothing outside `managed` may import from it.
  - A test pins both rules, the way `ApplicationContextTest` pins controller scoping.
- **Liquibase:** its own changelog file, `managed-queries.changelog.xml`, included from the master.
- **UI:** its own feature folder, `ui/src/app/managed/`, behind a lazy route. It talks to its own endpoints and imports nothing from the explorer's pages.

If it later needs to ship or switch off independently, a subproject can follow.
The rule that nothing depends on `managed` is what makes that extraction a
simple move.

## Datasources

### Config

A datasource lives inside its environment, so it is nested there. This keeps
the URL (`/env/{env}/ds/{ds}/…`) and the permission scope (`prod/reporting`) the
same shape as the config.

```yaml
environments:
  production:
    clusters:
      main: { … }                        # as today
    datasources:
      reporting:
        cluster: main                    # or server: main-replica-b
        servers: [analytics, replica]    # roles it may use, in order of preference
        allow-primary: false             # decision 10: true is the only way onto a primary
        username: sqlx_query_ro          # created by the operator (decision 3)
        password: ${PROD_REPORTING_QUERY_PASSWORD}
        limits:
          max-execution-time: 30s
          max-result-bytes: 1MB          # decision 2
          max-rows: 100000
          max-concurrent-runs: 2
  sharded:
    datasources:
      app:
        clusters: "*"                    # decision 8: one datasource per cluster, named app-<cluster>
        servers: [replica]
        username: sqlx_query_ro
        password: ${SHARDED_QUERY_PASSWORD}
```

**Binding.** The `managed` package binds `environments.*.datasources` with its
own `@ConfigurationProperties`. `EnvironmentDefinition` ignores the key, which
the binder already does for unknown keys. So the environment package learns
nothing about datasources.

**Global ceilings** live under `managed-queries.limits`, for example a 5-minute
time limit and a 10 MB result. A datasource can't configure past them; startup
refuses the config if it does.

### Checked at startup

These checks need only config, so they hold even when nothing can be reached.

- The environment, cluster or server it names exists. A `clusters: "*"` shorthand expands to at least one cluster.
- It can reach a server with one of its `servers` roles. A primary is only allowed with `allow-primary: true`.
- **The run-as user isn't the environment's own monitoring user**, meaning the `username` the server already connects with. Reusing it would give queries whatever the analyser can do.
- Its limits are within the global ceilings.

### Checked on first use

This never happens at startup. Startup never depends on connectivity, which is
a CLAUDE.md rule.

- **Grants.** Run `SHOW GRANTS FOR CURRENT_USER()` and look for anything beyond `SELECT`: `INSERT`, `UPDATE`, `DELETE`, `CREATE`, `DROP`, `ALTER`, `ALL`, `SUPER`, `FILE`, `PROCESS` and so on.
  - The datasource's health records the result, and the Admin page shows it.
  - A datasource whose user can write is **refused** unless it sets `accept-write-grants: true`. See open question 1.

### Connections

- **One small Hikari pool per datasource.** The run-as credentials aren't the monitoring credentials, so the server's existing pool can't be reused.
- **The environment package owns the pool.** It adds `DatabaseServer.openQueryPool(username, password, size)`, guarded exactly as `getDataSource` is. While the environment is cached it throws `ServerOfflineException`, which decision 9 turns into a refused run ("served from its cache; pull to go live"). The "a fresh cache connects to nothing" guarantee therefore stays in one place.
- The pool closes when the environment goes back to its cache.

## Running a query

A run is **asynchronous**. Cancelling needs it, and every tool researched that
supports cancelling works this way. The request answers with a run id at once;
the UI polls `GET …/runs/{id}` until the run finishes.

1. **Authorize.** This happens by URL, before the controller: `query:run`, or `query:author` for unsaved SQL.
2. **Validate the statement.**
   - jsqlparser must find exactly one statement, and it must be a `SELECT` or a `WITH … SELECT`.
   - A plain or `FOR SHARE` read only: anything else, including `SELECT … INTO` and `SELECT … FOR UPDATE`, is refused.
   - This is a second line of defence. The grants and the read-only session are the real guarantee.
3. **Record the run** (`status = queued`) *before* anything executes. This row is the audit record: it says who, what, where and with which limits.
4. **Wait for a slot** on the datasource's executor (`max-concurrent-runs`). Then pick the server: the first healthy one whose role is in `servers`. Fail rather than fall back to a primary.
5. **Prepare the session** on the borrowed connection:
   ```sql
   SET SESSION TRANSACTION READ ONLY;
   SET SESSION max_execution_time = <ms>;     -- top-level SELECTs only
   SET SESSION sql_select_limit = <max-rows + 1>;
   SET SESSION lock_wait_timeout = 5;         -- never queue behind DDL
   SELECT CONNECTION_ID();                    -- recorded, for KILL QUERY
   ```
   Also:
   - connect with `allowMultiQueries=false`;
   - set `Statement.setQueryTimeout` to the time limit plus a margin, as a backstop for what `max_execution_time` doesn't cover;
   - reset the session on return, as `SamplingSession` does, and throw away a connection that couldn't be reset.
6. **Bind the parameters** as `PreparedStatement` values; see [Parameters](#parameters).
7. **Stream the rows** into the Parquet writer. Stop at `max-rows + 1` rows or when the encoded size passes `max-result-bytes`, and record the run as `truncated` (the "fetch one more" approach from the research).
8. **Finish** with status `ok`, `truncated`, `timeout`, `cancelled`, `error` or `refused`, plus rows, bytes, duration and the error message.

**Cancelling** sends `KILL QUERY <connection id>` from another connection in the
same pool. MySQL lets a user kill their own connections without `PROCESS`. The
run ends as `cancelled`.

## Parameters

- A statement names parameters as `:name`. Before preparing, they are rewritten to `?` and bound in order.
- **Types:** text, number, date (with the date math the app already uses, `now-7d`), and enum (a fixed list in v1; lists filled by another saved query come later).
- **Never substituted into SQL text.** A parameter is always a value, never an identifier, so it can't choose a table or a column. The research found this is what forced Redash to restrict text parameters.
- **Optional clauses** use Metabase's form, `[[ AND region = :region ]]`. The clause is dropped as a whole, by its brackets, when the parameter has no value. It is never assembled from pieces.
- A parameter can be marked `secret`. Its value is bound but shown as `••••` in the run record and in the UI.

## Data model

All of this lives in the explorer database, in the feature's own changelog.

```
managed_query          id, environment, datasource, name, description, owner_id,
                       current_version_id, visualization (json), created_on, updated_on, archived_on
managed_query_version  id, query_id, version, sql, parameters (json), author_id, created_on
                       -- immutable: an edit is a new version, and a run points at the version it ran
managed_query_run      id, query_id (null if unsaved), version_id (null if unsaved), environment, datasource,
                       server, db_user, triggered_by (user), token_id, sql (exact text run),
                       parameters (json; secrets masked), limits (json; what was applied),
                       connection_id, status, rows, bytes, columns (json),
                       queued_on, started_on, finished_on, error,
                       result_path, result_purged_on
```

**The run table is the audit log (`sqlx-tev.4`).**
- A run row is written before execution and only its outcome columns are updated afterwards.
- Nothing deletes rows. Retention clears `result_path` and sets `result_purged_on`; it never removes the row.
- Runs that are refused (by validation, an offline environment, write grants or a full queue) are recorded as `refused` with the reason.
- Requests refused by authorization never reach the controller, so they appear in the application log rather than the table.

## Results (Parquet)

- **Location:** `<results-dir>/<environment>/<datasource>/<query-id>/<run-id>.parquet`, or `…/adhoc/<run-id>.parquet` for SQL that isn't saved. One directory per job is what decision 2 asked for, and it makes a job's results easy to delete together.
- **Schema:** each file has an Avro schema built from the run's `ResultSetMetaData`:
  - integers become `long`;
  - floating types become `double`;
  - `DECIMAL` becomes a string, so no precision is lost;
  - dates and times become ISO-8601 strings;
  - binary becomes `bytes`, truncated per value with the truncation flagged;
  - everything else becomes a string.
  
  The column list is also copied to `managed_query_run.columns`, so listing runs never opens a file.
- **Writing:** the file is opened, written and closed within the run, the same way `ParquetSink` works since `sqlx-fx2`. A run that dies leaves no half-written file that looks finished.
- **Retention (`sqlx-tev.8`):** keep results for N days (default 30), and always keep at least M runs per job (default 10). Deletion runs in batches on a schedule. Run rows are kept forever.

## API

Everything is under `/api/sql-explorer/env/{env}/ds/{ds}/`, so the URL alone
gives the authorization scope. That's the reason the path was changed in
`sqlx-e13.6`. In `SecurityConfig`, `{ds}` becomes `Scope.datasource(env, ds)`.

| Method & path | Permission |
|---|---|
| `GET  /env/{env}/datasources` | `explorer:read`; only the datasources the caller holds `query:read` on |
| `GET  …/ds/{ds}/queries`, `GET …/queries/{id}`, `GET …/queries/{id}/versions` | `query:read` |
| `POST …/queries`, `PUT …/queries/{id}` (new version), `DELETE …/queries/{id}` (archive) | `query:author` |
| `POST …/queries/{id}/runs` (body: parameter values, optional version) | `query:run` |
| `POST …/runs` (unsaved SQL) | `query:author` |
| `GET  …/runs/{runId}`, `GET …/runs/{runId}/rows?offset=&limit=` | `query:read` |
| `POST …/runs/{runId}/cancel` | `query:run`, and only the runner or an Admin |
| `GET  /env/{env}/datasources/{ds}/health` | Admin |

## UI

- **Routes:** `/{env}/ds/{ds}/queries`, `…/queries/:id`, `…/queries/:id/runs/:runId`, under the same environment-first paths as the rest of the app (`sqlx-e13.7`).
- **Links share what was asked, not the result.** A query link carries its parameter values in the URL (`?p.region=eu`), so a link means "this query with these values" and isn't run until someone runs it. A run link shows that stored run.
- **Composer:** a SQL editor with the existing highlighter, completing from `SchemaRepository` for the environment. That is the one shared piece (decision 11). Parameters are declared beside the SQL.
- **Result views:** a table that uses the `.data-table.ledger` styles, and a chart (time series or bar) whose settings are saved with the query. Which chart library is open question 2.

## Order of work

Each step can be shipped on its own:

1. **`sqlx-tev.3`:** datasource config, the startup and first-use checks, the query pool in the environment API, the session safeguards.
2. **`sqlx-tev.6` and `sqlx-tev.4`:** the run path and the run table as the audit log. At first this is unsaved SQL only, so it can be tested before the composer exists.
3. **`sqlx-tev.5`:** saved queries, versions and parameters.
4. **`sqlx-tev.7`:** the table and chart views.
5. **`sqlx-tev.8`:** retention.

MCP tools for managed queries are out of v1. Tokens (`sqlx-e13.5`) will make
them safe to add later.

## Open questions

1. **Run-as users with write grants.** I propose refusing them by default, with `accept-write-grants: true` as an explicit, recorded override. The alternative is to warn only. Refusing matches "the database is the guarantee"; warning matches decision 3, which leaves users to the operator.
2. **Chart library.** The UI has none; the graph lens draws on a canvas itself. Options: uPlot (small, time series), Chart.js (general), or drawing SVG ourselves for the two chart types v1 needs.
3. **Concurrency defaults:** two runs at a time per datasource, and a queue of at most 20 before runs are refused.
4. **Who may cancel a run:** the person who started it and Admins only, or anyone with `query:run` on the datasource?
