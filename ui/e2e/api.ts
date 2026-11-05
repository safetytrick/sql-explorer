import {Page} from '@playwright/test';

/**
 * A small, deterministic corpus, stubbed at the network boundary.
 *
 * <p>The suite needs no MySQL, no Spring Boot and no docker — that is the
 * property that keeps it runnable in the existing frontend CI job rather than
 * needing a job with service containers, which is how the last e2e suite came
 * to be never run at all (sqlx-6jy).
 *
 * <p>Any call NOT stubbed here is failed deliberately rather than allowed
 * through. A test that quietly reached a real backend would pass on a
 * developer's machine and fail in CI, which is worse than not covering it.
 */

const BASE = '**/api/sql-explorer';

function metrics(digest: string, executed: number, avgMs: number) {
  return {
    fullTableScan: false, executed, errors: 0, warnings: 0,
    totalTime: executed * avgMs * 1e9, maxTime: avgMs * 2e9, averageTime: avgMs * 1e9,
    rowsSent: 1, averageRowsSent: 1, rowsScanned: 10, averageRowsScanned: 10,
    tempTables: 0, tempDiskTables: 0, rowsSorted: 0, sortMergePasses: 0,
    firstSeen: '2026-05-04T15:49:26', lastSeen: '2026-08-30T12:00:00',
    digest, digestList: [digest]
  };
}

/** A row of `/schemas`. */
function schemaRollup(name: string) {
  return {name, numberOfQueries: 3, totalQueryTime: 1000, numberOfTables: 2, tags: []};
}

/** A row of `/tables`, the list the tables page renders. */
function tableRollup(name: string, schema: string) {
  return {name, schema, numberOfQueries: 3, totalQueryTime: 1000, tags: [], view: false};
}

function profile(name: string, schema: string, perfDigest: string, executed = 100) {
  return {
    id: `primary@2026-08-30T00:00:00Z-${name}`,
    query: `select * from \`${schema}\`.\`${name}\` where id = ?`,
    type: 'SELECT', digest: `v3:${name}`, normalizedDigest: `v3:${name}`,
    schemaName: schema, schemas: [schema], tables: [`${schema}.${name}`],
    queryActivityStatus: 'ACTIVE', runtimeMetrics: metrics(perfDigest, executed, 5),
    mergedChildren: [], repeatedBlocks: [], perfDigests: null,
    selectColumns: [], criteriaColumns: [], groupByColumns: [], joinColumns: [],
    mismatchedJoins: [], analysisFailed: false, analysisSkipped: false,
    isDml: false, isMultiSchema: false, truncated: false, modifiedForAnalysis: false,
    sourcedFiles: [], sourceFileName: null, lineNumber: 0,
    analysisFailureReason: null, generatedSource: null
  };
}

export const ORDERS = profile('orders', 'app', 'aaaa1111');
export const USERS = profile('users', 'app', 'bbbb2222', 50);
export const SESSIONS = profile('sessions', 'ops', 'cccc3333', 7);

const ALL = [ORDERS, USERS, SESSIONS];

const SCHEMA_TREE = {
  schemas: [
    {name: 'app', tables: [
      {name: 'orders', columnCount: 4, queryCount: 1, warn: false, view: false, inSchema: true},
      {name: 'users', columnCount: 3, queryCount: 1, warn: false, view: false, inSchema: true},
      {name: 'dead_app', columnCount: 2, queryCount: 0, warn: false, view: false, inSchema: true}
    ]},
    {name: 'ops', tables: [
      {name: 'sessions', columnCount: 5, queryCount: 1, warn: false, view: false, inSchema: true},
      {name: 'dead_ops', columnCount: 2, queryCount: 0, warn: false, view: false, inSchema: true}
    ]},
    {name: 'mysql', tables: [
      {name: 'dead_sys', columnCount: 9, queryCount: 0, warn: false, view: false, inSchema: true}
    ]}
  ]
};

/** `mysql` carries `system`; the others carry nothing. Keyed by schema name. */
const SCHEMA_TAGS: Record<string, {id: number; name: string; path: string; type: string}[]> = {
  mysql: [{id: 1, name: 'system', path: '/schema/mysql', type: 'schema'}]
};

const COVERAGE = {
  environment: 'regression', loaded: true, loadedOn: '2026-08-30T12:00:00Z',
  originTable: 'sys.x$statement_analysis',
  sources: [{
    sourceRef: 'primary', origin: 'PERFORMANCE_SCHEMA', capturedAt: '2026-08-30T12:00:00Z',
    profiles: 3, window: {from: '2026-05-04T15:49:26', to: '2026-08-30T12:00:00', days: 118},
    findings: [{
      signal: 'DIGEST_CAPACITY', verdict: 'OK', measurement: '0 digests lost',
      basis: 'Performance_schema_digest_lost on the source server',
      explanation: 'The source server reports that it never ran out of room for a digest.'
    }]
  }],
  clusters: [{
    cluster: 'primary', servers: ['primary'], schemaServer: 'primary', fallback: false, passedOver: [], failure: null,
    current: true, fromSnapshot: false, readAt: '2026-08-30T12:00:00Z'
  }],
  drift: []
};

/** Matches a search request the way the server does, for the filters we exercise. */
function search(body: Record<string, unknown>) {
  const regex = typeof body.queryRegex === 'string' && body.queryRegex.trim()
    ? new RegExp(body.queryRegex, 'i') : null;
  const schemas = (body.schemas as string[] | null) ?? [];
  const results = ALL
    .filter(p => !regex || regex.test(p.query))
    .filter(p => !schemas.length || schemas.includes(p.schemaName));
  return {
    total: results.length,
    results: results.map(profile => ({profile, tags: []})),
    facets: {tables: {}, schemas: {app: 2, ops: 1}}  // the shape QuerySearchResult.Facets serializes to
  };
}

export async function stubApi(page: Page) {
  const json = (body: unknown) => ({status: 200, contentType: 'application/json', body: JSON.stringify(body)});

  // Registered FIRST so it is matched LAST: Playwright tries handlers in
  // reverse registration order, so the catch-all has to go in before the
  // specific routes or it swallows every one of them.
  await page.route(`${BASE}/**`, route => route.abort('failed'));

  await page.route(`${BASE}/queries/search`, async route =>
    route.fulfill(json(search(JSON.parse(route.request().postData() || '{}')))));
  await page.route(`${BASE}/schema-tree`, route => route.fulfill(json(SCHEMA_TREE)));
  await page.route(`${BASE}/coverage`, route => route.fulfill(json(COVERAGE)));
  // Answers POSITIONALLY — one entry per target, in the order sent — which is
  // the contract the client zips against (sqlx-3ai).
  await page.route(`${BASE}/tags/targets`, async route => {
    const targets: {type: string; schema: string; table: string}[] =
      JSON.parse(route.request().postData() || '[]');
    return route.fulfill(json(targets.map(target =>
      target.type === 'schema' ? SCHEMA_TAGS[target.schema] ?? [] : [])));
  });
  await page.route(`${BASE}/tags`, route => route.fulfill(json([{name: 'system', color: '#f5a623'}])));
  await page.route(`${BASE}/tags/type/*`, route => route.fulfill(json(['system'])));
  await page.route(`${BASE}/environments`, route => route.fulfill(json({
    defaultEnvironment: 'regression',
    // Two, so a test can tell "the environment travelled" apart from "there
    // was only ever one" (sqlx-29f).
    environments: [
      {name: 'regression', servers: [], sources: {
        loaded: true, sourceCount: 1, loadedOn: '2026-08-30T12:00:00Z', importStats: null, resident: true
      }},
      {name: 'staging', servers: [], sources: {
        loaded: true, sourceCount: 1, loadedOn: '2026-08-30T12:00:00Z', importStats: null, resident: true
      }}
    ]
  })));
  // Answers PER ENVIRONMENT, keyed off the header the interceptor stamps. That
  // is what lets a test assert an environment switch changed the DATA and not
  // just the address — the distinction a regression turned on (sqlx-29f).
  // `/schemas` answers per environment too, and it matters more than /tables
  // does: the root redirects to /tables, so the switch's re-instantiation
  // bounce through `/` never leaves that route. /schemas is a page where the
  // bounce genuinely changes component, which is what makes it the probe for
  // whether switching reloads at all (sqlx-29f).
  await page.route(`${BASE}/schemas`, route => {
    const environment = route.request().headers()['x-sql-explorer-environment'];
    return route.fulfill(json(environment === 'staging'
      ? [schemaRollup('staging_only')]
      : [schemaRollup('app'), schemaRollup('ops')]));
  });
  await page.route(`${BASE}/tables`, route => {
    const environment = route.request().headers()['x-sql-explorer-environment'];
    const rows = environment === 'staging'
      ? [tableRollup('staging_only', 'ops')]
      : [tableRollup('orders', 'app'), tableRollup('events', 'app')];
    return route.fulfill(json(rows));
  });
  await page.route(`${BASE}/graph/v2/**`, route => route.fulfill(json({nodes: [], edges: []})));
}

/**
 * Pick an environment the way a person does: open the status in the nav and
 * choose from the list (sqlx-m6r.6). Was a <select>; tests name the action,
 * not the widget, so the next change to it touches only this.
 */
export async function chooseEnvironment(page: import('@playwright/test').Page, name: string): Promise<void> {
  await page.locator('.env-current').click();
  await page.locator('.env-option', {hasText: name}).click();
}
