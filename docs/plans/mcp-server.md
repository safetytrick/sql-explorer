# Add an MCP server

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
> the context window. Writes (tags/notes) deliberately not exposed —
> revisit as v2 with confirmation UX. Tested end-to-end in `mcp/test.mjs`:
> real stdio transport against a stubbed backend (routing, headers,
> environment overrides, text endpoints, error mapping, crash resilience).
> Setup docs in `mcp/README.md`. The `getAllAnalyzedSql()` per-call rebuild
> (TECH_DEBT #2) still amplifies under tool loops — unchanged, flagged.

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
that `getAllAnalyzedSql()` re-merges per call (TECH_DEBT #2) — MCP loops will
amplify that; consider fixing that first.

## Open questions

- Who's the consumer — Claude Desktop/Cowork over stdio, or HTTP (streamable)
  transport for remote use? Determines transport module and auth story (the app
  currently has none — `auth/UserResource` is a stub).
- Should tag/note *writes* be exposed, or read-only for v1?
- Same JVM vs sidecar module calling REST?

## Effort: M for a read-only in-process server with ~10 tools.
