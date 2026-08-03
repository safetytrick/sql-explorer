/**
 * End-to-end exercise of the MCP server over real stdio, against a stubbed
 * SQL Explorer backend. Run with: npm test (in mcp/).
 */
import assert from 'node:assert/strict';
import http from 'node:http';
import {Client} from '@modelcontextprotocol/sdk/client/index.js';
import {StdioClientTransport} from '@modelcontextprotocol/sdk/client/stdio.js';

// ── stub backend ──

const requests = [];
const stub = http.createServer((request, response) => {
  let body = '';
  request.on('data', chunk => body += chunk);
  request.on('end', () => {
    requests.push({ method: request.method, url: request.url, body,
      environment: request.headers['x-sql-explorer-environment'] ?? null });
    const route = `${request.method} ${request.url.split('?')[0]}`;
    const routes = {
      'GET /api/sql-explorer/schemas': [{ name: 'tdb', numberOfQueries: 12 }],
      'GET /api/sql-explorer/tables/tdb': [{ name: 'member_session', schema: 'tdb', view: false, numberOfQueries: 9 }],
      'GET /api/sql-explorer/metadata/table/tdb/active_member_sessions': {
        schema: 'tdb', name: 'active_member_sessions', type: 'VIEW',
        underlyingTables: [{ normalizedSchemaName: 'tdb', normalizedTableName: 'member_session' }],
        viewColumns: [{ name: 'id', computed: false,
          sources: [{ table: { normalizedSchemaName: 'tdb', normalizedTableName: 'member_session' }, normalizedColumn: 'id' }] }]
      },
      'GET /api/sql-explorer/metadata/table/tdb/branch/create': 'CREATE TABLE `branch` (`id` char(36) NOT NULL)',
      'POST /api/sql-explorer/queries/search': {
        total: 1,
        results: [{ profile: { id: 'q1', query: 'select 1', normalizedDigest: 'abc' }, tags: [] }],
        facets: { tables: { 'tdb.member_session': 1 }, schemas: { tdb: 1 } }
      },
      'POST /api/sql-explorer/util/normalize': { text: 'select 1', repeats: [{ start: 0, end: 8, count: 3 }] },
      'GET /api/sql-explorer/analyzed-sql/reports/slow': [{ id: 'q9' }]
    };
    if (route === 'GET /api/sql-explorer/boom') {
      response.writeHead(500); response.end('kaboom'); return;
    }
    const payload = routes[route];
    if (payload === undefined) {
      response.writeHead(404); response.end('not stubbed: ' + route); return;
    }
    const isText = typeof payload === 'string';
    response.writeHead(200, { 'Content-Type': isText ? 'text/plain' : 'application/json' });
    response.end(isText ? payload : JSON.stringify(payload));
  });
});
await new Promise(resolve => stub.listen(0, '127.0.0.1', resolve));
const stubUrl = `http://127.0.0.1:${stub.address().port}/api/sql-explorer`;

// ── client over stdio ──

const transport = new StdioClientTransport({
  command: process.execPath,
  args: ['server.mjs'],
  env: { ...process.env, SQL_EXPLORER_URL: stubUrl, SQL_EXPLORER_ENVIRONMENT: 'staging' }
});
const client = new Client({ name: 'sql-explorer-mcp-test', version: '0.0.0' });
await client.connect(transport);

const parse = result => JSON.parse(result.content[0].text);

// tool inventory
const tools = await client.listTools();
const names = tools.tools.map(tool => tool.name).sort();
assert.ok(names.includes('search_queries') && names.includes('get_table_structure') && names.includes('normalize_sql'));
assert.equal(names.length, 16);

// simple GET + default environment header
const schemas = parse(await client.callTool({ name: 'list_schemas', arguments: {} }));
assert.equal(schemas[0].name, 'tdb');
assert.equal(requests.at(-1).environment, 'staging');

// per-call environment override
await client.callTool({ name: 'list_tables', arguments: { schema: 'tdb', environment: 'prod' } });
assert.equal(requests.at(-1).environment, 'prod');
assert.equal(requests.at(-1).url, '/api/sql-explorer/tables/tdb');

// view lineage passes through
const structure = parse(await client.callTool({ name: 'get_table_structure', arguments: { schema: 'tdb', table: 'active_member_sessions' } }));
assert.equal(structure.type, 'VIEW');
assert.equal(structure.underlyingTables[0].normalizedTableName, 'member_session');
assert.equal(structure.viewColumns[0].sources[0].normalizedColumn, 'id');

// text endpoint
const ddl = await client.callTool({ name: 'get_table_ddl', arguments: { schema: 'tdb', table: 'branch' } });
assert.ok(ddl.content[0].text.startsWith('CREATE TABLE'));

// search posts JSON with a default limit
const search = parse(await client.callTool({ name: 'search_queries', arguments: { queryRegex: 'select', schemas: ['tdb'] } }));
assert.equal(search.total, 1);
const searchRequest = JSON.parse(requests.at(-1).body);
assert.equal(searchRequest.limit, 25);
assert.deepEqual(searchRequest.schemas, ['tdb']);

// normalize posts text/plain and returns repeats
const normalized = parse(await client.callTool({ name: 'normalize_sql', arguments: { sql: 'select 1 or 1' } }));
assert.equal(normalized.repeats[0].count, 3);
assert.equal(requests.at(-1).body, 'select 1 or 1');

// report enum
const report = parse(await client.callTool({ name: 'run_report', arguments: { name: 'slow', limit: 5 } }));
assert.equal(report[0].id, 'q9');
assert.ok(requests.at(-1).url.endsWith('/reports/slow?limit=5'));

// backend failure → tool error, not crash
const failure = await client.callTool({ name: 'get_query', arguments: { id: 'missing' } });
assert.equal(failure.isError, true);
assert.ok(failure.content[0].text.includes('HTTP 404'));

// server survives the failure
const again = parse(await client.callTool({ name: 'list_schemas', arguments: {} }));
assert.equal(again[0].name, 'tdb');

await client.close();
stub.close();
console.log('all mcp server scenarios pass');
