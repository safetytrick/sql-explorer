# SQL Explorer MCP server

An [MCP](https://modelcontextprotocol.io) server (stdio transport) that lets
Claude Desktop / Cowork talk to a **running** SQL Explorer instance. It
proxies the REST API — start SQL Explorer (and its MySQL) the usual way
first.

`add_tag` and `remove_tag` are the only tools that write; everything else
reads. Notes and source loading are deliberately not exposed.

## Tools

Schema & tables: `list_schemas`, `list_tables`, `schema_tree`,
`get_table_structure` (includes view→base lineage and per-column view
sources), `get_table_ddl`, `get_foreign_keys`, `get_table_stats`,
`get_column_usage`, `get_graph`.

Queries: `search_queries` (the workbench's filter engine), `get_query`,
`run_report` (slow / chatty / full-scan / …).

Tags: `list_tags`, `get_queries_by_tag`, and the two writes — `add_tag` /
`remove_tag`. Both name their target as `queryDigest`, `schema`, or
`schema` + `table` (the server builds the `/query/…` or `/schema/…/table/…`
path from that — `queryDigest` is a profile's `digest` field, which is what
the UI's tag editor keys on). Adding a tag that is already there is a no-op; removing
one deletes just that tagging, not the tag everywhere else.

SQL utilities (stateless): `clean_sql`, `normalize_sql` (returns the
collapsed repeats too).

Every data tool accepts an optional `environment` argument
(the `X-Sql-Explorer-Environment` header); responses are truncated at 60k
characters.

## Setup

```bash
cd mcp && npm install
```

### Claude Desktop / Cowork

Add to `claude_desktop_config.json` (Settings → Developer → Edit Config),
or register the equivalent connector in Cowork:

```json
{
  "mcpServers": {
    "sql-explorer": {
      "command": "node",
      "args": ["/absolute/path/to/sql-explorer/mcp/server.mjs"],
      "env": {
        "SQL_EXPLORER_URL": "http://localhost:8080/api/sql-explorer",
        "SQL_EXPLORER_ENVIRONMENT": "local"
      }
    }
  }
}
```

Both env vars are optional — the URL defaults to
`http://localhost:8080/api/sql-explorer` and the environment to the
backend's default.

## Test

`npm test` runs the server over real stdio against a stubbed backend and
exercises the tool surface (routing, headers, environment overrides, text
endpoints, tag-write request shapes, error mapping).
