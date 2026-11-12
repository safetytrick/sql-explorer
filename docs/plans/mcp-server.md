# Add an MCP server

> **Update 2026-11-11 — with sign-in on, `/mcp` needs a personal access
> token (sqlx-e13.5).** `Authorization: Bearer sqlx_…`, made on the Access
> tokens page (`/tokens`, linked from the signed-in user in the nav). A
> browser session also works, which is how the MCP page lists the tools.
> Without either it is a 401. Each tool call is authorized in
> `McpToolRunner` against the environment it reads: `explorer:read` for the
> reads, `commentary:write` for `add_tag` / `remove_tag`; `clean_sql` and
> `normalize_sql` read no environment and need only the authentication. A
> call that is not allowed is a tool error naming the missing permission.
> With no environment named, the default is used if the caller may read it,
> else the first one they may. A token acts as its owner, so what it writes
> is authored by them; the token's id goes to the `sql-explorer.audit` log
> beside it. What a token may do is min(its owner's grants as of their
> latest sign-in, its ceiling): see `AccessTokenService` for why "latest
> sign-in", and why tokens expire (90 days at most by default,
> `sql-explorer.auth.token-max-lifetime`). The MCP page's snippets read the
> token from `SQL_EXPLORER_TOKEN` so a checked-in `.mcp.json` holds no
> secret. With sign-in off (`mode: none`) nothing changes: `/mcp` is open.
>
> **Update 2026-11-09 — served over HTTP by the backend; the Node sidecar is
> gone.** `POST /api/sql-explorer/mcp` is MCP's streamable HTTP in its
> stateless form (MCP Java SDK 2.1, `HttpServletStatelessServerTransport`,
> Jackson 3). A client is now given a URL, where the sidecar needed `npm
> install`, the checkout's absolute path and two environment variables in
> every client's config. The reasons the sidecar won in August no longer
> hold: Claude Code takes HTTP servers directly (`claude mcp add --transport
> http`), Claude Desktop reaches one through `mcp-remote`, and the
> "re-calling REST" concern is gone because the tools now call the
> resources in-process. The same 18 tools, contributed per domain package
> (`*McpTools` implementing `McpToolProvider`, beside the package-private
> resources they call, so a tool cannot drift from its endpoint). Tools run
> on the request thread, so the environment comes from the request's
> `X-Sql-Explorer-Environment` header (REST moved the environment into its
> path, sqlx-e13.6; `/mcp` is one servlet URL so it keeps the header), and a
> tool's `environment` argument overrides it — checked, and an unknown name
> is refused. A request carrying a foreign
> `Origin` is refused (DNS rebinding). Covered over real HTTP by
> `ApplicationContextTest`. Reaching it through the ngrok tunnel needs the
> Google login, which an MCP client cannot pass; it is for this machine.
>
> What follows is the August record, kept for the reasoning.

> **Status: implemented 2026-08-02** as `mcp/` — a read-only stdio MCP
> server for Claude Desktop / Cowork (the plan's suggested default,
> recorded). **Architecture choice, resolving the open question:** a Node
> sidecar proxying the REST API of a *running* instance, not an in-process
> Spring module. Rationale: stdio clients spawn the server per session —
> spawning a small node script beats spawning the Spring app (which needs
> MySQL and a source load); the "out-of-process means re-calling REST"
> concern from this plan applies to servers that would otherwise share the
> heap, not to a desktop-client bridge; and read-only is enforced by
> construction (only read endpoints are wrapped). 16 tools: schema/table
> lookup (including view→base lineage and per-column view sources from the
> views work), query search via the workbench filter engine, canned
> reports, tags, the graph, and the clean/normalize utilities (with
> collapsed repeats). Per-tool optional `environment` argument maps to the
> `X-Sql-Explorer-Environment` header; 60k-char response truncation guards
> the context window. Tested end-to-end in `mcp/test.mjs`: real stdio
> transport against a stubbed backend (routing, headers, environment
> overrides, text endpoints, error mapping, crash resilience). Setup docs
> in `mcp/README.md`. The `getAllAnalyzedSql()` per-call rebuild
> (TECH_DEBT, backend perf) still amplifies under tool loops — unchanged, flagged.
>
> **Update 2026-08-07 — tag writes are in v1 (18 tools).** `add_tag` and
> `remove_tag` shipped; Michael confirmed this as intended v1 scope. This
> supersedes the plan's original "read-only for v1, revisit writes as v2
> with confirmation UX" position, which was a guess at the risk, not a
> constraint anyone asked for. What changed the calculus: tagging is the
> one place the tool is *meant* to write — it's how a human curates the
> corpus, and an assistant that can read every query but not label what it
> found makes the human retype its conclusions. The blast radius is small
> and reversible by construction: a tag is an annotation, never query or
> schema data; `add_tag` on an existing tag is a no-op rather than a
> duplicate; `remove_tag` deletes one tagging, not the tag everywhere it
> is used; and the tool name regex rejects anything but
> `[A-Za-z0-9_-]`. `remove_tag` resolves the id by path first and errors
> with the tags that *are* there when it finds no match, so a wrong guess
> issues no DELETE. Notes and source loading stay unexposed — notes are
> free text (no cheap validation) and source loading is neither an
> annotation nor reversible.

## Current state

No AI/MCP code or deps exist (repo-wide grep clean). Relevant posture:

- Spring Boot 4.0.6, servlet MVC only (`spring-boot-starter-webmvc`,
  `build.gradle:90-92`) — no WebFlux.
- OpenAPI already generated: springdoc 3.0.3 + Scalar UI at
  `/api/sql-explorer/v3/api-docs` (`build.gradle:99-101`, `application.yml:25-28`)
  — ready-made tool schemas.
- 18 REST controllers; Swagger `@Tag` names already group them
  (`SqlProfile`, `Query`, `Schema`, `Graph`, `Tags`, …).
- Every request is environment-scoped via the `X-Sql-Explorer-Environment`
  header → request-scoped `EnvironmentContext`
  (`environment/EnvironmentContext.java:18-30`) — an in-process MCP transport
  must either synthesize that header or resolve `EnvironmentRegistry` directly
  (the startup loaders already do the latter).
- `settings.gradle` includes only `models`; a new `mcp/` module would be the
  third include and inherits the `allprojects` toolchain/docker wiring.

## Proposed approach

Embed an MCP server in the existing Spring app (avoid a separate process — the
analyzed corpus lives in heap in `SqlRepositoryManager`, so out-of-process
would mean re-calling REST anyway; simplest is the official MCP Java SDK or
Spring AI's `spring-ai-starter-mcp-server-webmvc`, both fine with servlet MVC).
Expose a curated tool set, not a blanket REST mirror:

| Tool | Backed by |
|---|---|
| `search_queries` | `POST /queries/search` (`profiles/QuerySearchResource.java:23`) — highest value |
| `get_query` | `GET /analyzed-sql/{id}` |
| `get_table_profile` / `get_table_stats` / `get_column_usage` | `SqlProfileResource` + `TableInsightsResource` |
| `get_table_ddl` / `get_table_structure` / `get_foreign_keys` | `SchemaResource` (`/metadata/table/**`) |
| `list_schemas` / `list_tables` / `schema_tree` | rollup + tree endpoints |
| `run_report` | `GET /analyzed-sql/reports/{name}` (10 canned names, `SqlProfileReportService.java:29-38`) |
| `clean_sql` / `normalize_sql` | `QueryCleanupResource` — stateless, trivial wins |
| `tag_query` / `list_tags` / `get_by_tag` | commentary resources (write access — gate it) |

Each tool takes an optional `environment` argument defaulting to the registry
default. Add per-tool result truncation (profiles carry full SQL text) and note
that `getAllAnalyzedSql()` re-merges per call (TECH_DEBT, backend perf) — MCP loops will
amplify that; consider fixing that first.

## Open questions

- Who's the consumer — Claude Desktop/Cowork over stdio, or HTTP (streamable)
  transport for remote use? Determines transport module and auth story (the app
  currently has none — `auth/UserResource` is a stub).
  *Answered: stdio for Claude Desktop/Cowork.*
- Should tag/note *writes* be exposed, or read-only for v1?
  *Answered 2026-08-07: tag writes yes, notes no — see the status block.*
- Same JVM vs sidecar module calling REST?
  *Answered: sidecar — see the status block.*

## Effort: M for a read-only in-process server with ~10 tools.
