#!/usr/bin/env node
/**
 * SQL Explorer MCP server — stdio transport.
 *
 * Proxies a running SQL Explorer instance's REST API as MCP tools for
 * Claude Desktop / Cowork. Everything is read-only except `add_tag` and
 * `remove_tag`; notes and source management are deliberately not exposed.
 *
 * Configuration (environment variables):
 *   SQL_EXPLORER_URL          base API url, default http://localhost:8080/api/sql-explorer
 *   SQL_EXPLORER_ENVIRONMENT  default environment name (X-Sql-Explorer-Environment header)
 */
import {McpServer} from '@modelcontextprotocol/sdk/server/mcp.js';
import {StdioServerTransport} from '@modelcontextprotocol/sdk/server/stdio.js';
import {z} from 'zod';

const BASE_URL = (process.env.SQL_EXPLORER_URL ?? 'http://localhost:8080/api/sql-explorer').replace(/\/$/, '');
const DEFAULT_ENVIRONMENT = process.env.SQL_EXPLORER_ENVIRONMENT;

/** Responses are truncated at this many characters so one tool call can't flood the context. */
const MAX_RESULT_CHARS = 60_000;

async function api(path, { method = 'GET', body, asText = false, environment } = {}) {
  const headers = {};
  const env = environment ?? DEFAULT_ENVIRONMENT;
  if (env) {
    headers['X-Sql-Explorer-Environment'] = env;
  }
  if (body !== undefined) {
    headers['Content-Type'] = typeof body === 'string' ? 'text/plain' : 'application/json';
  }
  const response = await fetch(BASE_URL + path, {
    method,
    headers,
    body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body)
  });
  if (!response.ok) {
    const detail = (await response.text()).slice(0, 300);
    throw new Error(`${method} ${path} failed: HTTP ${response.status} ${detail}`);
  }
  return asText ? response.text() : response.json();
}

function toResult(data) {
  let text = typeof data === 'string' ? data : JSON.stringify(data, null, 2);
  if (text.length > MAX_RESULT_CHARS) {
    text = text.slice(0, MAX_RESULT_CHARS) + `\n… truncated (${text.length} chars total — narrow the request)`;
  }
  return { content: [{ type: 'text', text }] };
}

function toError(error) {
  return { isError: true, content: [{ type: 'text', text: String(error?.message ?? error) }] };
}

/** Wrap a handler so API failures come back as MCP tool errors, not protocol crashes. */
const safely = handler => async args => {
  try {
    return toResult(await handler(args ?? {}));
  } catch (error) {
    return toError(error);
  }
};

const environment = z.string().optional()
  .describe('SQL Explorer environment name; defaults to the server default');

const server = new McpServer({ name: 'sql-explorer', version: '0.1.0' });

// ── schemas & tables ──

server.tool('list_schemas',
  'Schemas seen in the analyzed queries, with query counts and total query time.',
  { environment },
  safely(({ environment }) => api('/schemas', { environment })));

server.tool('list_tables',
  'Tables ranked by total query time. Optionally scoped to one schema. Each row carries query count, tags, and whether it is a view.',
  { schema: z.string().optional().describe('scope to this schema'), environment },
  safely(({ schema, environment }) =>
    api(schema ? `/tables/${encodeURIComponent(schema)}` : '/tables', { environment })));

server.tool('schema_tree',
  'Every known schema and table (from metadata and queries) with column counts, query counts, analyzer-warning and view flags.',
  { environment },
  safely(({ environment }) => api('/schema-tree', { environment })));

server.tool('get_table_structure',
  'Table metadata: columns, indexes, foreign keys, type (TABLE/VIEW) — and for views, the base tables (underlyingTables) and per-column lineage (viewColumns).',
  { schema: z.string(), table: z.string(), environment },
  safely(({ schema, table, environment }) =>
    api(`/metadata/table/${encodeURIComponent(schema)}/${encodeURIComponent(table)}`, { environment })));

server.tool('get_table_ddl',
  'The CREATE TABLE / CREATE VIEW statement for a table, loaded from the live server.',
  { schema: z.string(), table: z.string(), environment },
  safely(({ schema, table, environment }) =>
    api(`/metadata/table/${encodeURIComponent(schema)}/${encodeURIComponent(table)}/create`, { asText: true, environment })));

server.tool('get_foreign_keys',
  'Foreign keys in and out of a table, with per-column type-mismatch flags.',
  { schema: z.string(), table: z.string(), environment },
  safely(({ schema, table, environment }) =>
    api(`/metadata/table/${encodeURIComponent(schema)}/${encodeURIComponent(table)}/foreign-keys`, { environment })));

server.tool('get_table_stats',
  'Aggregate query stats for one table: distinct queries, executions, total time, read/write split.',
  { schema: z.string(), table: z.string(), environment },
  safely(({ schema, table, environment }) =>
    api(`/table/${encodeURIComponent(schema)}/${encodeURIComponent(table)}/stats`, { environment })));

server.tool('get_column_usage',
  'Per-column usage for one table: type, PK/FK, query roles (select/criteria/join/group by), query counts.',
  { schema: z.string(), table: z.string(), environment },
  safely(({ schema, table, environment }) =>
    api(`/table/${encodeURIComponent(schema)}/${encodeURIComponent(table)}/columns`, { environment })));

server.tool('get_graph',
  "One schema's table-relationship graph: nodes with query counts, FK edges (with type-mismatch flags), co-query edges, and view→base-table edges.",
  { schema: z.string(), environment },
  safely(({ schema, environment }) =>
    api(`/graph/v2/schema/${encodeURIComponent(schema)}`, { environment })));

// ── queries ──

server.tool('search_queries',
  'Search analyzed queries. All filters optional. Returns matching profiles (normalized SQL, tables, join/criteria columns, runtime metrics) plus table/schema facet counts.',
  {
    queryRegex: z.string().optional().describe('case-insensitive regex over the SQL text (invalid patterns fall back to literal search)'),
    types: z.array(z.string()).optional().describe('statement types: SELECT, INSERT, UPDATE, DELETE, OTHER'),
    schemas: z.array(z.string()).optional().describe('only queries in these schemas'),
    excludeSchemas: z.array(z.string()).optional(),
    tables: z.array(z.string()).optional().describe('"schema.table" keys the query must reference'),
    tableMode: z.enum(['ANY', 'ALL']).optional(),
    tags: z.array(z.string()).optional(),
    fullScan: z.enum(['YES', 'NO']).optional().describe('queries with full table scans'),
    analysisFailed: z.boolean().optional(),
    sort: z.enum(['IMPACT', 'AVG_TIME', 'MAX_TIME', 'AVG_ROWS_SCANNED', 'SELECTIVITY', 'LAST_SEEN']).optional(),
    limit: z.number().int().min(1).max(100).optional().describe('default 25'),
    offset: z.number().int().min(0).optional(),
    environment
  },
  safely(({ environment, ...request }) =>
    api('/queries/search', { method: 'POST', body: { limit: 25, ...request }, environment })));

server.tool('get_query',
  'One analyzed query profile by id or digest: normalized SQL, tables, join/criteria/select columns, runtime metrics, collapsed repeats.',
  { id: z.string().describe('profile id or digest'), environment },
  safely(({ id, environment }) => api(`/analyzed-sql/${encodeURIComponent(id)}`, { environment })));

server.tool('run_report',
  'A canned query report.',
  {
    name: z.enum(['all', 'join-across-types', 'slow', 'chatty', 'analysis-failures', 'analysis-skipped',
      'parse-failures', 'truncated', 'full-scan', 'multi-schema']),
    limit: z.number().int().min(1).max(100).optional(),
    environment
  },
  safely(({ name, limit, environment }) =>
    api(`/analyzed-sql/reports/${name}${limit ? `?limit=${limit}` : ''}`, { environment })));

// ── tags ──

server.tool('list_tags',
  'All tag definitions with colors and usage counts (queries / tables / schemas).',
  { environment },
  safely(({ environment }) => api('/tags', { environment })));

server.tool('get_queries_by_tag',
  'The analyzed queries carrying a tag.',
  { tag: z.string(), environment },
  safely(({ tag, environment }) => api(`/analyzed-sql/tag/${encodeURIComponent(tag)}`, { environment })));

// ── tag writes ──

/**
 * The path a tag hangs off: /query/{digest}, /schema/{name}, or
 * /schema/{name}/table/{name}. Stored verbatim, so no URL encoding here —
 * the backend derives the tag's type from the last two segments.
 */
function tagPath({ queryDigest, schema, table }) {
  if (queryDigest) {
    if (schema || table) {
      throw new Error('tag either a query or a schema/table, not both');
    }
    return `/query/${queryDigest}`;
  }
  if (!schema) {
    throw new Error('name a target: queryDigest, schema, or schema + table');
  }
  return table ? `/schema/${schema}/table/${table}` : `/schema/${schema}`;
}

const tagTarget = {
  queryDigest: z.string().optional()
    .describe('tag a query — its `digest` field from search_queries / get_query. Use that field, ' +
      'not `id`: the UI hangs query tags off the digest, and a tag on anything else stays invisible there'),
  schema: z.string().optional().describe('tag this schema, or with `table`, a table in it'),
  table: z.string().optional().describe('tag this table inside `schema`')
};

server.tool('add_tag',
  'Tag a query, table, or schema. Re-tagging something returns the existing tag rather than duplicating it.',
  {
    tag: z.string().regex(/^[\w-]+$/, 'letters, numbers, underscores and hyphens only')
      .describe('tag name'),
    ...tagTarget,
    environment
  },
  safely(({ tag, queryDigest, schema, table, environment }) =>
    api('/tags', { method: 'POST', body: { name: tag, path: tagPath({ queryDigest, schema, table }) }, environment })));

server.tool('remove_tag',
  'Remove a tag from a query, table, or schema. Only that one tagging is deleted — the tag stays on everything else carrying it.',
  { tag: z.string().describe('tag name, matched case-insensitively'), ...tagTarget, environment },
  safely(async ({ tag, queryDigest, schema, table, environment }) => {
    const path = tagPath({ queryDigest, schema, table });
    const existing = await api(`/tags/path?path=${encodeURIComponent(path)}`, { environment });
    const match = existing.find(candidate => candidate.name.toLowerCase() === tag.toLowerCase());
    if (!match) {
      const names = existing.map(candidate => candidate.name);
      throw new Error(`no tag "${tag}" on ${path} — ` +
        (names.length ? `tags there: ${names.join(', ')}` : 'it has no tags'));
    }
    await api(`/tag/${match.id}`, { method: 'DELETE', asText: true, environment });
    return { removed: match };
  }));

// ── SQL utilities (stateless) ──

server.tool('clean_sql',
  'Clean performance-schema digest text into parseable SQL (placeholder runs collapsed, truncation repaired).',
  { sql: z.string() },
  safely(({ sql }) => api('/util/clean', { method: 'POST', body: sql })));

server.tool('normalize_sql',
  'Normalize a query the way the digest pipeline does: aliases stripped, repeated blocks collapsed (with located repeats), formatted.',
  { sql: z.string(), clean: z.boolean().optional().describe('run the digest-text cleaner first (default true)') },
  safely(({ sql, clean }) =>
    api(`/util/normalize${clean === false ? '?clean=false' : ''}`, { method: 'POST', body: sql })));

const transport = new StdioServerTransport();
await server.connect(transport);
