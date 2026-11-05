import {expect, test} from '@playwright/test';
import {stubApi} from './api';

test.beforeEach(async ({page}) => {
  await stubApi(page);
});

test('the root opens on the schemas list, the starting page', async ({page}) => {
  await page.goto('/');

  await expect(page).toHaveURL(/\/schemas$/);
});

/**
 * An unknown URL gets a real not-found page, and the address that failed is
 * shown rather than swallowed.
 *
 * <p>Worth a test because the documentation had drifted from it twice over:
 * CLAUDE.md said there was no catch-all route and that a bad URL rendered an
 * empty outlet while resetting the address bar. Both halves were wrong, and
 * the drift was only visible from a real browser on a real route — which is
 * the gap this suite exists to close (sqlx-6jy).
 */
test('an unknown URL gets a not-found page that names the bad address', async ({page}) => {
  await page.goto('/no-such-page');

  await expect(page).toHaveURL(/\/no-such-page$/);
  await expect(page.locator('.view-title')).toContainText('Not found');
  await expect(page.locator('.card-empty').first()).toContainText('/no-such-page');
});

/** It offers a way out, so a stale link is not a dead end. */
test('the not-found page links back into the app', async ({page}) => {
  await page.goto('/no-such-page');

  await page.locator('app-not-found').getByRole('link', {name: 'Queries'}).click();

  await expect(page).toHaveURL(/\/queries/);
  await expect(page.locator('.q-row')).toHaveCount(3);
});

/** Deep links must survive a cold load, not only in-app navigation. */
test('a deep link loads directly', async ({page}) => {
  await page.goto('/unqueried');

  await expect(page.locator('.view-title')).toContainText('Unqueried tables');
});

test('the nav moves between the main views', async ({page}) => {
  await page.goto('/queries');
  await expect(page.locator('.q-row')).toHaveCount(3);

  await page.getByRole('link', {name: 'Unqueried', exact: true}).click();
  await expect(page).toHaveURL(/\/unqueried/);
  await expect(page.locator('.view-title')).toContainText('Unqueried tables');
});

/** Tables lists only queried tables, and says where the others are (sqlx-c8w.13). */
test('the tables list points to the unqueried tables it leaves out', async ({page}) => {
  await page.goto('/tables');
  await page.locator('.unqueried-link').click();

  await expect(page).toHaveURL(/\/unqueried/);
});

/** The page was renamed; a link to its old address still arrives (sqlx-om1.4). */
test('the old /coverage address opens Captured, keeping its query', async ({page}) => {
  await page.goto('/coverage?env=staging');

  await expect(page).toHaveURL(/\/captured\?env=staging$/);
  await expect(page.locator('.view-title')).toHaveText('Captured');
});

/** A tag on a row narrows the list it is on, rather than leaving for another page (sqlx-c8w.9). */
test('clicking a tag on a tables row filters the list in place', async ({page}) => {
  await page.route('**/api/sql-explorer/tables', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify([
      {name: 'orders', schema: 'app', numberOfQueries: 3, totalQueryTime: 1000, view: false, tags: [{id: 1, name: 'system'}]},
      {name: 'events', schema: 'app', numberOfQueries: 3, totalQueryTime: 1000, view: false, tags: []}
    ])
  }));
  await page.goto('/tables');
  await expect(page.locator('.data-table tbody tr')).toHaveCount(2);

  await page.locator('.data-table tbody a', {hasText: 'system'}).click();

  await expect(page).toHaveURL(/\/tables\?.*tags=system/);
  await expect(page.locator('.data-table tbody tr')).toHaveCount(1);
});

/**
 * The graph is a view of the workbench (sqlx-aoy.16). Links to the old graph
 * page have been shared, so they must open the same question there: a schema
 * graph is a search scoped to the schema with every table drawn.
 */
test('an old schema graph link opens the workbench graph of that schema', async ({page}) => {
  await page.goto('/graph/app?var-layers=fk,join');

  await expect(page).toHaveURL(/\/queries\?/);
  const url = new URL(page.url());
  expect(url.searchParams.get('view')).toBe('graph');
  expect(url.searchParams.get('schemas')).toBe('app');
  expect(url.searchParams.get('var-tables')).toBe('all');
  expect(url.searchParams.get('var-layers')).toBe('fk,join');
  await expect(page.locator('app-graph-lens canvas').first()).toBeVisible();
  await expect(page.locator('.q-row')).toHaveCount(0);
});

/** Switching back to the list keeps the search and drops the graph's own params. */
test('the workbench switches between the list and the graph of the same search', async ({page}) => {
  await page.goto('/queries');
  await expect(page.locator('.q-row')).toHaveCount(3);

  await page.locator('.bar-right').getByText('graph', {exact: true}).click();
  await expect(page).toHaveURL(/view=graph/);
  await expect(page.locator('app-graph-lens canvas').first()).toBeVisible();

  await page.locator('.bar-right').getByText('list', {exact: true}).click();
  await expect(page).not.toHaveURL(/view=graph/);
  await expect(page.locator('.q-row')).toHaveCount(3);
});
