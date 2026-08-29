# Add a live EXPLAIN plan feature

> **Status: decided 2026-08-28, not started.** Bead `sqlx-cgh`. This was
> option (c) of the explain-parser plan, which shipped (a)+(b) — the four
> paste formats — and left (c) as a product/security question. That question
> is answered: build it. The gating below is therefore a requirement list,
> not a recommendation.

Running `EXPLAIN <query>` against an environment's servers from the query
detail view removes the paste step and opens the door to explain-over-time.

## What makes this different from everything else the app does

Today sql-explorer **never executes a statement against an analyzed server.**
It reads schema metadata (`DatabaseMetaData`, `INFORMATION_SCHEMA.STATISTICS`)
and it reads `performance_schema` / `sys.x$statement_analysis`. Both are
reads of the server's own bookkeeping. This feature is the first time the app
would run SQL that the *user's data* flows through.

That is the whole reason this needed a decision, and it is why the gating
below is not negotiable garnish. A bug here runs a statement on a production
database.

## Requirements (from `sqlx-cgh`'s design field — carried, not re-derived)

1. **Per-environment opt-in, defaulting off.** An environment that does not
   say yes cannot be explained against. Off is the safe value and therefore
   the default; a missing config key means off, not on.
2. **`EXPLAIN`-prefixed statements only, built server-side from the stored
   digest text — never from client-supplied SQL.** The client sends an
   identifier (digest), not a statement. The server looks up the profile,
   prefixes `EXPLAIN`, and runs that. There is no code path where a string
   from the request body reaches the database.
3. **A short statement timeout,** and read-only connection expectations
   documented in `DATA_ENVIRONMENTS.md`.

Requirement 2 is the load-bearing one. If the endpoint ever accepts a
statement, the other two stop mattering much.

## Current state, by inspection

- **`DatabaseServer`** (`environment/DatabaseServer.java`) already owns a
  lazily-built `HikariDataSource` per server and hands it out via
  `getDataSource()`. There is a connection pool to run this on; nothing new
  is needed at that layer.
- **`ServerDefinition`** carries `jdbcUrl`, `username`, `password`, `sources`
  — and no flags. The opt-in from requirement 1 is a new field here (and on
  `EnvironmentDefinition` if the switch should be per-environment rather than
  per-server; the requirement says per-environment, so prefer the latter and
  let servers inherit).
- **`EnvironmentRegistry` / `EnvironmentContext`** are how the active
  environment is resolved for a request; the endpoint reads the target server
  through them rather than taking a server from the client.
- **Incoming EXPLAIN statements are skipped at ingest.** `EXPLAIN` is in
  `ignoredFirstTokens` (`evidence/SqlEvidenceInputService.java:75+`), so
  EXPLAINs observed in the corpus are not analyzed. Unrelated to running
  them, but worth knowing before someone "fixes" that list.
- **The rendering layer already exists and is format-compatible.**
  `explain-parser.ts` parses `EXPLAIN FORMAT=JSON` into the same typed rows
  as the paste formats, so a `FORMAT=JSON` endpoint plugs into the existing
  table with no frontend rework — badges, `est. rows`, full-scan
  highlighting, table links all come for free.

## Proposed approach

**Backend.** A new endpoint on the query side, `POST /analyzed-sql/{digest}/explain`
(or `GET` — it is a read, but it executes, so `POST` signals that better).
It resolves the digest to a stored profile, refuses if the active
environment is not opted in, builds `EXPLAIN FORMAT=JSON <stored text>`,
runs it on `DatabaseServer.getDataSource()` with `Statement.setQueryTimeout`,
and returns the raw JSON document. It never sees SQL from the request.

Two things to decide while building:

- **Which server**, when an environment has several. The profile knows the
  schema it ran in; the environment may have one server or many. Simplest
  correct answer is to explain on the server the profile's source came from,
  and to say so in the response.
- **Parameterised statements.** A digest is normalised — `?` placeholders,
  not values. `EXPLAIN SELECT … WHERE id = ?` is not runnable as-is. This is
  the single biggest open question in the feature, and it is why `sqlx-hlu`
  ("capture example queries with their complete parameters") is effectively a
  prerequisite for explaining anything parameterised. Either land `hlu`
  first, or ship this restricted to statements with no parameters and say so
  in the UI.

**Frontend.** A button on the query detail view that calls the endpoint and
feeds the result to the existing parser. The paste view stays — it is the
fallback when an environment is not opted in, and it is how someone explains
a statement the corpus does not contain.

## Effort

M–L, as estimated in the original plan. The endpoint and the wiring are
straightforward; the parameter question above is what makes it L rather
than M.

## Open questions

- **Parameters** — see above. Blocks explaining most real statements.
  Related: `sqlx-hlu`.
- **Explain-over-time.** The original pitch mentioned it. Storing plans and
  diffing them is a much larger feature than running one; treat it as a
  follow-up rather than scope here.
- **`EXPLAIN ANALYZE`** actually *executes* the statement rather than
  planning it. It must not be reachable from this feature. Worth an explicit
  guard rather than a convention, given requirement 2's spirit.
