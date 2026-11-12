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
/** Environment-scoped endpoints are served under /env/{env}/ (sqlx-e13.6). */
export const SCOPED = `${BASE}/env/*`;

/** The environment a scoped request names in its path. */
export function envOf(url: string): string | undefined {
  return /\/api\/sql-explorer\/env\/([^/]+)\//.exec(url)?.[1];
}

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
  return {name, schema, numberOfQueries: 3, totalQueryTime: 1000, tags: [], view: false,
    rowsEstimate: 1200, sizeBytes: 163840};
}

function profile(name: string, schema: string, perfDigest: string, executed = 100, source = 'primary') {
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
    sourcedFiles: [], sourceFileName: null, lineNumber: 0, sources: [source],
    analysisFailureReason: null, generatedSource: null
  };
}

export const ORDERS = profile('orders', 'app', 'aaaa1111');
export const USERS = profile('users', 'app', 'bbbb2222', 50);
export const SESSIONS = profile('sessions', 'ops', 'cccc3333', 7, 'replica');

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

/**
 * No schema carries a tag. `system` used to be one, on `mysql`; it is a
 * schema-role annotation now (sqlx-n5j.14), and the scope specs that exclude
 * `system` pass against that — which is the point: the link means what it did.
 */
const SCHEMA_TAGS: Record<string, {id: number; name: string; path: string; type: string}[]> = {};

/** `mysql` is MySQL's own: the annotation the `system` tag was migrated to. */
const SCHEMA_ROLES = [{
  id: 1, target: {type: 'schema', schema: 'mysql', table: '', digest: '', column: ''},
  namespace: 'schema-role', key: 'role', value: 'system', confidence: 'confirmed', basis: 'migrated from the system tag',
  sourceKind: 'person', sourceName: null, author: {email: 'admin'}, recordedOn: '2026-08-30T12:00:00Z'
}];

const GENERATORS = [
  {name: 'doctrine-orm', description: 'Doctrine ORM (PHP)', rules: [], status: null, confirmation: null},
  {name: 'hibernate', description: 'Hibernate ORM (Java)', rules: [], status: null, confirmation: null}
];

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

/** `/collations`: one table mixing collations, one on a single encoding, one join whose sides differ (sqlx-n5j.5). */
function encoding(schema: string, table: string, collations: string[]) {
  return {
    schema, table, view: false, textColumns: collations.length,
    predominantCharset: 'utf8mb4', predominantCollation: collations[0],
    mixedCharsets: false, mixedCollations: new Set(collations).size > 1,
    encodings: collations.map((collation, i) => ({characterSet: 'utf8mb4', collation, columns: [`c${i}`]})),
    outliers: collations.slice(1).map((collation, i) => ({column: `c${i + 1}`, characterSet: 'utf8mb4', collation}))
  };
}

const COLLATIONS = {
  tables: [
    encoding('app', 'orders', ['utf8mb4_0900_ai_ci', 'utf8mb4_bin']),
    encoding('app', 'users', ['utf8mb4_0900_ai_ci'])
  ],
  joins: [{
    left: {schema: 'app', table: 'orders', column: 'user_code', characterSet: 'utf8mb4', collation: 'utf8mb4_bin'},
    right: {schema: 'app', table: 'users', column: 'code', characterSet: 'utf8mb4', collation: 'utf8mb4_0900_ai_ci'},
    charsetDiffers: false, queries: 1, executions: 100, exampleDigest: 'v3:orders'
  }]
};

/** The table lens's workload cards (sqlx-n5j.9, .10): answered as zeros, as the server does for a quiet table. */
const WRITE_RATE = {executions: 0, rowsAffected: 0, executionsPerDay: 0, rowsPerDay: 0, from: null, to: null,
  byType: [], statements: []};
const LOCK_RISK = {statementCount: 0, longThresholdMs: 1000, longStatements: 0, longExecutionsPerDay: 0,
  maxTimeMs: 0, maxAverageTimeMs: 0, quietHoursAvailable: false, statements: []};

/** Matches a search request the way the server does, for the filters we exercise. */
function search(body: Record<string, unknown>) {
  const regex = typeof body.queryRegex === 'string' && body.queryRegex.trim()
    ? new RegExp(body.queryRegex, 'i') : null;
  const schemas = (body.schemas as string[] | null) ?? [];
  const excludeQueries = (body.excludeQueries as string[] | null) ?? [];
  // the server merges only these sources' data; one source per fixture, so keeping its rows is the same
  const sources = (body.sources as string[] | null) ?? [];
  const results = ALL
    .filter(p => !regex || regex.test(p.query))
    .filter(p => !schemas.length || schemas.includes(p.schemaName))
    .filter(p => !excludeQueries.includes(p.digest))
    .filter(p => !sources.length || p.sources.some(source => sources.includes(source)));
  const perSource: Record<string, number> = {};
  results.forEach(p => p.sources.forEach(source => { perSource[source] = (perSource[source] ?? 0) + 1; }));
  return {
    total: results.length,
    results: results.map(profile => ({profile, tags: []})),
    // the shape QuerySearchResult.Facets serializes to
    facets: {tables: {}, schemas: {app: 2, ops: 1}, sources: perSource}
  };
}

/** `/user` with sign-in on: a dev-idp style user with a group or two. */
export const SIGNED_IN = {
  mode: 'oidc',
  user: {id: 2, email: 'viewer@example.com', name: 'Viewer', createdOn: '2026-08-30T12:00:00Z'},
  issuer: 'http://localhost:18190', subject: 'viewer', groups: ['sqlx:viewer']
};

export async function stubApi(page: Page) {
  const json = (body: unknown) => ({status: 200, contentType: 'application/json', body: JSON.stringify(body)});

  // Registered FIRST so it is matched LAST: Playwright tries handlers in
  // reverse registration order, so the catch-all has to go in before the
  // specific routes or it swallows every one of them.
  await page.route(`${BASE}/**`, route => route.abort('failed'));

  await page.route(`${SCOPED}/queries/search`, async route =>
    route.fulfill(json(search(JSON.parse(route.request().postData() || '{}')))));
  await page.route(`${SCOPED}/schema-tree`, route => route.fulfill(json(SCHEMA_TREE)));
  // the environment's loaded sources, which the workbench offers as its source filter
  await page.route(`${SCOPED}/sources/active`, route => route.fulfill(json(['primary', 'replica'].map(sourceRef =>
    ({source: {sourceRef, createdOn: '2026-08-30T00:00:00Z'}, status: 'unknown', importStats: null})))));
  await page.route(`${SCOPED}/coverage`, route => route.fulfill(json(COVERAGE)));
  // Answers POSITIONALLY — one entry per target, in the order sent — which is
  // the contract the client zips against (sqlx-3ai).
  await page.route(`${SCOPED}/tags/targets`, async route => {
    const targets: {type: string; schema: string; table: string}[] =
      JSON.parse(route.request().postData() || '[]');
    return route.fulfill(json(targets.map(target =>
      target.type === 'schema' ? SCHEMA_TAGS[target.schema] ?? [] : [])));
  });
  // Annotations (sqlx-n5j.13): positional like tags/targets, and nothing is annotated but mysql's role.
  await page.route(`${SCOPED}/annotations/targets`, async route => {
    const targets: unknown[] = JSON.parse(route.request().postData() || '[]');
    return route.fulfill(json(targets.map(() => [])));
  });
  await page.route(`${SCOPED}/annotations/namespace/*`, route =>
    route.fulfill(json(route.request().url().endsWith('/schema-role') ? SCHEMA_ROLES : [])));
  await page.route(`${SCOPED}/notes/paths`, route => route.fulfill(json({})));
  await page.route(`${SCOPED}/generators`, route => route.fulfill(json(GENERATORS)));
  await page.route(`${SCOPED}/tags`, route => route.fulfill(json([{name: 'system', color: '#f5a623'}])));
  await page.route(`${SCOPED}/tags/type/*`, route => route.fulfill(json(['system'])));
  // signed in, as with sign-in on (sqlx-e13.1); sign-in.spec.ts overrides it
  await page.route(`${BASE}/user`, route => route.fulfill(json(SIGNED_IN)));
  await page.route(`${BASE}/environments`, route => route.fulfill(json({
    defaultEnvironment: 'regression',
    // Two, so a test can tell "the environment travelled" apart from "there
    // was only ever one" (sqlx-29f).
    environments: [
      {name: 'regression', servers: [], sources: {
        loaded: true, sourceCount: 1, loadedOn: '2026-08-30T12:00:00Z', importStats: null, resident: true
      }, sampling: {server: null, available: false, reason: 'no server with the sampling role'}},
      {name: 'staging', servers: [], sources: {
        loaded: true, sourceCount: 1, loadedOn: '2026-08-30T12:00:00Z', importStats: null, resident: true
      }}
    ]
  })));
  // Answers PER ENVIRONMENT, keyed off the path segment the interceptor adds. That
  // is what lets a test assert an environment switch changed the DATA and not
  // just the address — the distinction a regression turned on (sqlx-29f).
  // `/schemas` answers per environment too, and it matters more than /tables
  // does: the root redirects to /tables, so the switch's re-instantiation
  // bounce through `/` never leaves that route. /schemas is a page where the
  // bounce genuinely changes component, which is what makes it the probe for
  // whether switching reloads at all (sqlx-29f).
  await page.route(`${SCOPED}/schemas`, route => {
    const environment = envOf(route.request().url());
    return route.fulfill(json(environment === 'staging'
      ? [schemaRollup('staging_only')]
      : [schemaRollup('app'), schemaRollup('ops')]));
  });
  await page.route(`${SCOPED}/tables`, route => {
    const environment = envOf(route.request().url());
    const rows = environment === 'staging'
      ? [tableRollup('staging_only', 'ops')]
      : [tableRollup('orders', 'app'), tableRollup('events', 'app')];
    return route.fulfill(json(rows));
  });
  await page.route(`${SCOPED}/graph/v2/**`, route => route.fulfill(json({nodes: [], edges: []})));
  await page.route(`${SCOPED}/collations`, route => route.fulfill(json(COLLATIONS)));
  await page.route(`${SCOPED}/table/*/*/write-rate`, route => route.fulfill(json(WRITE_RATE)));
  await page.route(`${SCOPED}/table/*/*/lock-risk`, route => route.fulfill(json(LOCK_RISK)));
  await page.route(`${BASE}/graph/layout`, route => route.fulfill(json([])));
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
