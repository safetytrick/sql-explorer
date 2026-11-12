import {expect, test} from '@playwright/test';
import {stubApi} from './api';

const BASE = '**/api/sql-explorer';

const PLAN = {
  environment: 'local', schema: 'app', table: 'note', method: 'WINDOWS', key: ['id'],
  columns: [{name: 'title', type: 'VARCHAR(100)', characterSet: 'latin1', collation: null, signatures: []}],
  fullScanColumn: null, probeServer: null, unavailable: null,
  targets: [
    {cluster: 'shard1', server: 'shard1-etl', rowsEstimate: 30000, available: true, reason: null, maxRows: 20000,
      maxStatements: 25},
    {cluster: 'shard2', server: null, rowsEstimate: null, available: false,
      reason: 'cluster shard2 has no server with the sampling role', maxRows: 0, maxStatements: 0}],
  statements: ['SELECT MIN(`id`) AS min_key, MAX(`id`) AS max_key FROM `app`.`note`'],
  maxRows: 20000, maxStatements: 25, statementTimeLimitMs: 2000, pauseMs: 200, idsPerSignature: 20,
  windows: 'the first 5000 and last 5000 rows by id'
};

const RUN = {
  id: 7, environment: 'local', schema: 'app', table: 'note', probeServer: null, method: 'WINDOWS',
  columns: ['title'], status: 'done', requestedBy: 'admin', startedOn: '2026-11-10T20:00:00Z',
  finishedOn: '2026-11-10T20:00:05Z', rowsRead: 20000, statements: 14, statementsSoFar: 14, stale: false
};

const DETAIL = {
  run: RUN, plan: PLAN, error: null,
  result: {
    confidence: 'sampled',
    servers: [{cluster: 'shard1', server: 'shard1-etl', rowsEstimate: 30000, status: 'done', reason: null,
      complete: false, coverage: '20000 rows in 12 windows', windows: [], rowsRead: 20000, statementCount: 14,
      statements: [], columns: []}],
    columns: [{
      column: 'title', type: 'VARCHAR(100)', characterSet: 'latin1', collation: 'latin1_swedish_ci', rows: 20000,
      nulls: 0, nonAscii: 2, maxBytes: 12, distinctAtLeast: 3,
      verdict: 'mixed: UTF-8 bytes in 1 rows and genuine latin1 text in 1 rows in 20000 sampled rows',
      signatures: [{
        signature: 'UTF8_IN_LATIN1', label: 'UTF-8 bytes in a latin1 column', explanation: 'The application wrote UTF-8',
        heuristic: false, informational: false, rows: 1, ids: [{server: 'shard1-etl', ids: ['1']}],
        characters: [{matched: 'Ã©', codePoints: 'U+00C3 U+00A9', intended: 'é', rows: 1}]
      }]
    }]
  }
};

/**
 * Profile data (sqlx-n5j.3): the plan is shown before anything runs, a run
 * starts only once confirmed, and what it found opens column by column.
 */
test('a profile shows its plan, runs only when confirmed, and explains what it found', async ({page}) => {
  await stubApi(page);
  await page.route(`${BASE}/profile/table/app/note/plan**`, route =>
    route.fulfill({contentType: 'application/json', body: JSON.stringify(PLAN)}));
  let started: Record<string, unknown> | null = null;
  let runs: unknown[] = [];
  await page.route(`${BASE}/profile/table/app/note/runs`, async route => {
    if (route.request().method() === 'POST') {
      started = JSON.parse(route.request().postData() || '{}');
      runs = [RUN];
      await route.fulfill({contentType: 'application/json', body: JSON.stringify(RUN)});
    } else {
      await route.fulfill({contentType: 'application/json', body: JSON.stringify(runs)});
    }
  });
  await page.route(`${BASE}/profile/run/7`, route =>
    route.fulfill({contentType: 'application/json', body: JSON.stringify(DETAIL)}));

  await page.goto('/table/app/note/profile');

  await expect(page.locator('table.targets')).toContainText('shard1-etl');
  await expect(page.locator('table.targets tr.skipped')).toContainText('no server with the sampling role');
  await page.getByRole('button', {name: 'Run profile…'}).click();
  expect(started).toBeNull();
  await expect(page.locator('.confirm .ask')).toContainText('20,000');
  await page.getByRole('button', {name: 'Confirm and run'}).click();

  await expect(page).toHaveURL(/var-run=7/);
  expect(started).toMatchObject({confirmed: true, server: null});
  await expect(page.locator('tr.col-row')).toContainText('mixed');
  await page.locator('tr.col-row', {hasText: 'title'}).click();
  await expect(page).toHaveURL(/var-col=title/);
  await expect(page.locator('.finding')).toContainText('UTF-8 bytes in a latin1 column');
  await expect(page.locator('table.chars')).toContainText('é');
  await expect(page.locator('.ids')).toContainText('1');
});
